package com.wim.app

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Prosodic feature extraction for L4 reconstruction context.
 *
 * Ports lavrentiy.py extract_f0(), compute_hnr(), extract_prosodic_features(),
 * and build_prosodic_context() to Kotlin without numpy/scipy.
 *
 * Algorithm: normalized autocorrelation on a 50 ms window from each Whisper
 * segment.  F0 reads peak lag position (frequency); HNR reads peak magnitude
 * (harmonic vs noise ratio).  Same math, same primitive — see
 * ARCHITECTURE_PROSODIC_BRIDGING.md.
 *
 * Only runs at L4 (layer >= 4); injected into the Sonnet system prompt as a
 * PROSODIC CONTEXT block before reconstructWithSonnet() is called.
 */
object ProsodicAnalyzer {

    private const val TAG = "ProsodicAnalyzer"
    private const val SAMPLE_RATE = 16000
    private const val WAV_HEADER_BYTES = 44
    private const val MIN_F0_HZ = 80.0
    private const val MAX_F0_HZ = 500.0
    private const val WINDOW_MS = 50           // analysis window per sub-frame
    private const val WINDOW_SAMPLES = SAMPLE_RATE * WINDOW_MS / 1000  // 800

    data class SegmentFeatures(
        val startS: Double,
        val endS: Double,
        val text: String,
        val f0Mean: Double,
        val f0Var: Double,
        val energy: Double,
        val rateSps: Double,
        val pitchDirection: String,
        val hnr: Double
    )

    data class SessionSummary(val f0Mean: Double, val energyMean: Double, val rateMean: Double)
    data class Baseline(
        val f0Mean: Double,
        val energyMean: Double,
        val rateMean: Double,
        val samples: Int
    )

    // ── Public API ────────────────────────────────────────────────────────────

    fun extractFromWav(
        wavFile: File,
        segments: List<WhisperClient.TimedSegment>
    ): List<SegmentFeatures> {
        if (!wavFile.exists() || wavFile.length() <= WAV_HEADER_BYTES || segments.isEmpty()) {
            return emptyList()
        }
        return try {
            val samples = readWavSamples(wavFile)
            segments.mapNotNull { seg -> analyzeSegment(samples, seg) }.take(15)
        } catch (e: Exception) {
            Log.w(TAG, "Prosodic extraction failed: ${e.message}")
            emptyList()
        }
    }

    fun summarize(features: List<SegmentFeatures>): SessionSummary? {
        if (features.isEmpty()) return null
        val f0 = features.map { it.f0Mean }.filter { it > 0.0 }
        val energy = features.map { it.energy }.filter { it > 0.0 }
        val rate = features.map { it.rateSps }.filter { it > 0.0 }
        if (energy.isEmpty() || rate.isEmpty()) return null
        return SessionSummary(
            f0Mean = if (f0.isEmpty()) 0.0 else f0.average(),
            energyMean = energy.average(),
            rateMean = rate.average()
        )
    }

    fun buildProsodicContext(features: List<SegmentFeatures>, baseline: Baseline? = null): String {
        if (features.isEmpty()) return ""

        val speakerState = inferSpeakerState(features, baseline)
        val lines = mutableListOf("PROSODIC CONTEXT (acoustic features Whisper cannot transcribe):")

        for (feat in features) {
            val f0Str = if (feat.f0Mean > 0) "pitch:${feat.pitchDirection}" else "pitch:none"
            val energyLabel = if (baseline != null && baseline.energyMean > 0.0) when {
                feat.energy > baseline.energyMean * 1.45 -> "above-personal-baseline"
                feat.energy < baseline.energyMean * 0.60 -> "below-personal-baseline"
                else -> "near-personal-baseline"
            } else when {
                feat.energy > 0.1 -> "high"
                feat.energy < 0.02 -> "low"
                else -> "moderate"
            }
            val rateStr = if (feat.rateSps > 0) "rate:${feat.rateSps}syl/s" else "rate:0"
            lines.add("  [${feat.startS.fmt1()}-${feat.endS.fmt1()}s] $f0Str energy:$energyLabel $rateStr")
        }

        if (baseline != null) {
            lines.add("PERSONAL BASELINE: ${baseline.samples} sessions; pitch ${baseline.f0Mean.round1()}Hz, energy ${baseline.energyMean.round4()}, rate ${baseline.rateMean.round1()}syl/s")
        }
        if (speakerState.isNotEmpty()) lines.add("SPEAKER STATE: $speakerState")

        lines.add(
            "\nSTUTTER-PROSODIC RULES (use acoustic context to disambiguate):" +
            "\n- Repetition + rising pitch = genuine struggle → aggressive reconstruction" +
            "\n- Repetition + stable pitch = emphatic repetition, NOT a stutter → leave it" +
            "\n- Filler + flat energy + constant pitch = postponement stalling → strip it" +
            "\n- Filler + rising pitch = discourse marker ('you know?') → keep it" +
            "\n- Block + dropping energy = frustration/shutdown → reconstruct gently"
        )

        return lines.joinToString("\n")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun readWavSamples(wavFile: File): DoubleArray {
        val bytes = wavFile.readBytes()
        val audioBytes = bytes.size - WAV_HEADER_BYTES
        if (audioBytes <= 0) return DoubleArray(0)
        val buf = ByteBuffer.wrap(bytes, WAV_HEADER_BYTES, audioBytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = DoubleArray(audioBytes / 2)
        for (i in samples.indices) {
            samples[i] = buf.short.toDouble() / 32768.0
        }
        return samples
    }

    private fun analyzeSegment(
        samples: DoubleArray,
        seg: WhisperClient.TimedSegment
    ): SegmentFeatures? {
        val startIdx = (seg.startS * SAMPLE_RATE).toInt().coerceAtLeast(0)
        val endIdx = (seg.endS * SAMPLE_RATE).toInt().coerceAtMost(samples.size)
        val segDuration = (seg.endS - seg.startS).coerceAtLeast(0.01)
        if (endIdx - startIdx < WINDOW_SAMPLES / 2) return null

        val window = samples.sliceArray(startIdx until endIdx)
        val energy = rms(window)
        val hnr = computeHnr(window)

        // Split into sub-windows for F0 contour
        val nSubs = (segDuration / 0.1).toInt().coerceIn(2, 4)
        val subLen = window.size / nSubs
        val f0Values = mutableListOf<Double>()
        for (i in 0 until nSubs) {
            val sub = window.sliceArray(i * subLen until (i + 1) * subLen)
            val f0 = extractF0(sub)
            if (f0 > 0) f0Values.add(f0)
        }

        val f0Mean = if (f0Values.isNotEmpty()) f0Values.average() else 0.0
        val f0Var = if (f0Values.size >= 2) variance(f0Values) else 0.0

        val pitchDir = when {
            f0Values.size < 2 -> "flat"
            (f0Values.last() - f0Values.first()) > f0Mean * 0.1 &&
                    variance(f0Values) > 100 -> "erratic"
            f0Values.last() - f0Values.first() > f0Mean * 0.1 -> "rising"
            f0Values.first() - f0Values.last() > f0Mean * 0.1 -> "falling"
            else -> "flat"
        }

        val wordCount = seg.text.split(" ").count { it.isNotBlank() }
        val rateSps = if (segDuration > 0.1) (wordCount * 1.3 / segDuration) else 0.0

        return SegmentFeatures(
            startS = seg.startS,
            endS = seg.endS,
            text = seg.text,
            f0Mean = f0Mean.round1(),
            f0Var = f0Var.round1(),
            energy = energy.round4(),
            rateSps = rateSps.round1(),
            pitchDirection = pitchDir,
            hnr = hnr
        )
    }

    private fun extractF0(samples: DoubleArray): Double {
        val r = normalizedAutocorr(samples) ?: return 0.0
        val minLag = (SAMPLE_RATE / MAX_F0_HZ).toInt().coerceAtLeast(1)
        val maxLag = (SAMPLE_RATE / MIN_F0_HZ).toInt().coerceAtMost(r.size - 1)
        if (minLag >= maxLag) return 0.0

        var peakVal = 0.0
        var peakLag = 0
        for (k in minLag..maxLag) {
            if (r[k] > peakVal) { peakVal = r[k]; peakLag = k }
        }
        if (peakVal < 0.2 || peakLag == 0) return 0.0
        return (SAMPLE_RATE.toDouble() / peakLag).round1()
    }

    private fun computeHnr(samples: DoubleArray): Double {
        val r = normalizedAutocorr(samples) ?: return 20.0
        val minLag = (SAMPLE_RATE / MAX_F0_HZ).toInt().coerceAtLeast(1)
        val maxLag = (SAMPLE_RATE / MIN_F0_HZ).toInt().coerceAtMost(r.size - 1)
        if (minLag >= maxLag) return 20.0

        var rPeak = 0.0
        for (k in minLag..maxLag) { if (r[k] > rPeak) rPeak = r[k] }
        if (rPeak >= 1.0) return 30.0
        if (rPeak <= 0.0) return -10.0
        return (10.0 * log10(rPeak / (1.0 - rPeak))).coerceIn(-10.0, 30.0).round2()
    }

    private fun normalizedAutocorr(samples: DoubleArray): DoubleArray? {
        // Use a centre-extracted WINDOW_SAMPLES window to keep O(N²) fast on mobile
        val n = WINDOW_SAMPLES.coerceAtMost(samples.size)
        if (n < 2) return null
        val offset = (samples.size - n) / 2
        val x = DoubleArray(n) { samples[offset + it] - samples.slice(offset until offset + n).average() }
        val r = DoubleArray(n)
        for (k in 0 until n) {
            var sum = 0.0
            for (i in 0 until n - k) sum += x[i] * x[i + k]
            r[k] = sum
        }
        if (r[0] <= 0.0) return null
        val r0 = r[0]
        for (i in r.indices) r[i] /= r0
        return r
    }

    private fun inferSpeakerState(features: List<SegmentFeatures>, baseline: Baseline?): String {
        val avgEnergy = features.map { it.energy }.average()
        val avgF0 = features.map { it.f0Mean }.filter { it > 0 }.let { if (it.isEmpty()) 0.0 else it.average() }
        val avgRate = features.map { it.rateSps }.filter { it > 0 }.let { if (it.isEmpty()) 0.0 else it.average() }
        val risingCount = features.count { it.pitchDirection == "rising" }
        val erraticCount = features.count { it.pitchDirection == "erratic" }
        return when {
            baseline != null && baseline.f0Mean > 0 && avgF0 > baseline.f0Mean * 1.25 ->
                "pitch notably above this speaker's baseline — possible elevated load"
            baseline != null && baseline.energyMean > 0 && avgEnergy < baseline.energyMean * 0.55 ->
                "energy notably below this speaker's baseline — possible fatigue or shutdown"
            baseline != null && baseline.rateMean > 0 && avgRate > 0 && avgRate < baseline.rateMean * 0.60 ->
                "rate notably below this speaker's baseline — possible blocking or deliberate pacing"
            erraticCount > features.size / 3 -> "elevated stress — pitch erratic"
            risingCount > features.size / 2 -> "high cognitive load — rising pitch pattern"
            avgEnergy < 0.015 -> "low energy — fatigue or low affect"
            else -> ""
        }
    }

    private fun rms(samples: DoubleArray): Double {
        if (samples.isEmpty()) return 0.0
        return sqrt(samples.sumOf { it * it } / samples.size)
    }

    private fun variance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }

    private fun Double.round1() = (Math.round(this * 10.0).toDouble() / 10.0)
    private fun Double.round2() = (Math.round(this * 100.0).toDouble() / 100.0)
    private fun Double.round4() = (Math.round(this * 10000.0).toDouble() / 10000.0)
    private fun Double.fmt1() = "%.1f".format(this)
}
