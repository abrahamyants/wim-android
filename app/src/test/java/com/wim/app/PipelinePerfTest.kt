package com.wim.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Performance regression guards for the pure-logic pipeline, mirroring
 * Лаврентий's test_perf.py. These are NOT microbenchmarks — budgets are
 * deliberately generous (dev/CI machines vary wildly). They exist to catch an
 * accidental O(n^2) blowup in a text transform on a large transcript, which a
 * happy-path test on a 5-word string would never surface.
 */
class PipelinePerfTest {

    private val fillers = listOf("um", "uh", "like", "you know", "i mean")

    private fun budget(label: String, maxMillis: Long, block: () -> Unit) {
        val start = System.nanoTime()
        block()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("$label took ${ms}ms (budget ${maxMillis}ms)", ms < maxMillis)
    }

    // 1,200 DISTINCT words ≈ an 8-minute monologue in one take — already far past
    // WiM's tap-to-send envelope (real takes are seconds). Distinct words don't
    // collapse, so this drives every regex pass at full length. Pathological
    // *repetition* of any size is covered crash-free by PipelineAdversarialTest;
    // FALSE_START is O(n²) on punctuation-free text, which only bites at sizes WiM
    // never produces, so this guards the realistic envelope rather than that cliff.
    @Test fun disfluencyFilter_longTake_underBudget() {
        val text = (0 until 1_200).joinToString(" ") {
            when (it % 7) { 0 -> "um"; 3 -> "like"; 5 -> "go go"; else -> "word$it" }
        }
        budget("DisfluencyFilter 1.2k distinct words", 6000) {
            assertTrue(DisfluencyFilter.filter(text, fillers).isNotEmpty())
        }
    }

    @Test fun predictTriggers_20kWords_underBudget() {
        val text = (0 until 10_000).joinToString(" ") { "problem$it strength$it" }
        budget("predictTriggersInText 20k words", 6000) {
            assertNotNull(PhoneticRisk.predictTriggersInText(text))
        }
    }

    @Test fun repunctuate_hugeString_underBudget() {
        val text = "hello world i mean ".repeat(5_000)
        budget("Repunctuate 25k words", 3000) {
            assertNotNull(Repunctuate.apply(text))
        }
    }

    @Test fun rateGap_oneMillionAnalyses_underBudget() {
        budget("RateGapDetector 1e6 analyses", 3000) {
            var sink = 0
            for (i in 0 until 1_000_000) {
                if (RateGapDetector.analyze(i % 50, 2.0 + (i % 5), 3.0).gapDetected) sink++
            }
            assertTrue(sink >= 0)
        }
    }
}
