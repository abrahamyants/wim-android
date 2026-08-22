package com.wim.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Transcodes the recorder's WAV to AAC-LC (.m4a) immediately before upload.
 *
 * Why this exists: the recorder writes 16 kHz mono 16-bit PCM — 32 KB for every
 * second of speech, uncompressed. The direct path posts that straight to
 * OpenAI; the backend path base64-wraps it (+33%) and relays it through the
 * Cloud Function, so the bytes cross a phone uplink twice before transcription
 * can start. Upload, not inference, is where the wait lives.
 *
 * 64 kbps mono AAC is ~8 KB/s — a 4x reduction — and is chosen over the ~10x
 * available at 24-32 kbps deliberately. WiM's Layer 4 reads clinical detail out
 * of the audio (blocks, prolongations, part-word repetitions); squeezing hard
 * enough to blur that would trade a feature for a stopwatch.
 *
 * Nothing upstream changes. The WAV is still written, still analysed, still
 * snapshotted for silent-block bridging. This only touches the copy that flies.
 *
 * Every failure path returns null, and the caller uploads the original WAV. A
 * transcode problem must never cost the user their dictation.
 */
object AudioCompressor {

    private const val TAG = "WiM.AudioCompressor"
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 64_000
    private const val WAV_HEADER_BYTES = 44
    // Poll without blocking. The first version waited 10 ms on every input and
    // every output buffer; AAC emits a frame per 1024 samples, so a 20-second
    // take is ~300 frames and the waiting alone cost seconds — more than the
    // upload it was meant to save. Take whatever is ready, and only yield when
    // the codec has nothing at all.
    private const val POLL_NOW_US = 0L

    /** Below this there is nothing to win and the muxer overhead can exceed the saving. */
    private const val MIN_WORTHWHILE_BYTES = 32 * 1024

    /** A fresh path per call. L4 runs several transcriptions in parallel. */
    internal fun newOutputFile(wav: File): File {
        val parent = wav.parentFile ?: throw IllegalArgumentException("WAV has no parent directory")
        val prefix = (wav.nameWithoutExtension.take(40) + "-").padEnd(3, '-')
        return File.createTempFile(prefix, ".m4a", parent).apply { delete() }
    }

    /** Ask Android's own demuxer whether the finalized file has an audio track. */
    internal fun isPlayableM4a(file: File): Boolean {
        if (!file.isFile || file.length() <= 16L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * @return an .m4a sibling of [wav], or null to mean "send the WAV".
     */
    fun compressForUpload(
        wav: File,
        sampleRate: Int = 16_000,
        channels: Int = 1,
    ): File? {
        if (!wav.isFile || wav.length() <= WAV_HEADER_BYTES + MIN_WORTHWHILE_BYTES) return null

        val out = newOutputFile(wav)

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        val t0 = System.currentTimeMillis()

        try {
            val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            codec = MediaCodec.createEncoderByType(MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1

            RandomAccessFile(wav, "r").use { raf ->
                raf.seek(WAV_HEADER_BYTES.toLong())
                val pcm = ByteArray(64 * 1024)
                val info = MediaCodec.BufferInfo()
                var totalSamples = 0L
                var sawInputEos = false
                var sawOutputEos = false

                while (!sawOutputEos) {
                    var progressed = false

                    if (!sawInputEos) {
                        val inIndex = codec.dequeueInputBuffer(POLL_NOW_US)
                        if (inIndex >= 0) {
                            progressed = true
                            val buf: ByteBuffer = codec.getInputBuffer(inIndex) ?: continue
                            buf.clear()
                            val read = raf.read(pcm, 0, minOf(pcm.size, buf.capacity()))
                            // PCM frames consumed so far drive the timestamp; the
                            // muxer rejects out-of-order or repeated presentation
                            // times and drops the tail without saying so.
                            val ptsUs = totalSamples * 1_000_000L / sampleRate
                            if (read <= 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEos = true
                            } else {
                                buf.put(pcm, 0, read)
                                codec.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                                totalSamples += read / (2L * channels)
                            }
                        }
                    }

                    // Drain whatever the codec has before feeding it again.
                    val outIndex = codec.dequeueOutputBuffer(info, POLL_NOW_US)
                    if (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        progressed = true
                    }
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) throw IllegalStateException("format changed twice")
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIndex >= 0 -> {
                            val encoded = codec.getOutputBuffer(outIndex)
                            // Codec config bytes belong in the track format, not
                            // the sample stream — the muxer already has them.
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                info.size = 0
                            }
                            if (info.size > 0 && encoded != null && muxerStarted) {
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, encoded, info)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEos = true
                            }
                        }
                    }

                    // Nothing to give and nothing to take: hand the CPU back
                    // briefly rather than spin. Without this the loop is a busy
                    // wait; without the zero-timeout polls above it is a crawl.
                    if (!progressed) Thread.sleep(1)
                }
            }

            if (!muxerStarted) throw IllegalStateException("encoder produced no track")

            // MediaMuxer writes the MP4 index during stop(). The old code checked
            // and returned the file first, then stopped in a swallowed finally.
            // An unfinished file still begins with `ftyp`, so the backend accepted
            // it and OpenAI rejected it as undecodable. Finalization is part of
            // success now: any stop failure falls into the WAV fallback below.
            muxer?.stop() ?: throw IllegalStateException("muxer disappeared before finalization")
            muxerStarted = false
            muxer?.release()
            muxer = null

            if (!isPlayableM4a(out)) {
                throw IllegalStateException("finalized M4A has no playable audio track")
            }

            val before = wav.length()
            val after = out.length()
            if (after <= 0 || after >= before) {
                Log.w(TAG, "transcode gained nothing ($before -> $after) — sending WAV")
                out.delete()
                return null
            }
            Log.i(
                TAG,
                "wav ${before / 1024}KB -> m4a ${after / 1024}KB " +
                    "(${"%.1f".format(before.toDouble() / after)}x) in ${System.currentTimeMillis() - t0}ms"
            )
            return out
        } catch (e: Exception) {
            // Encoder availability varies by OEM. Losing the dictation is not an
            // acceptable price for a faster upload, so any failure falls back.
            Log.w(TAG, "transcode failed, sending WAV: ${e.message}")
            runCatching { out.delete() }
            return null
        } finally {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }
}
