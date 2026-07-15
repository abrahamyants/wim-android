package com.wim.app

import org.junit.Assert.assertTrue
import org.junit.Test

class ProsodicBaselineTest {
    @Test fun contextUsesPersonalBaselineInsteadOfOnlyGenericThresholds() {
        val features = listOf(
            ProsodicAnalyzer.SegmentFeatures(
                startS = 0.0, endS = 1.0, text = "hello",
                f0Mean = 230.0, f0Var = 12.0, energy = 0.02,
                rateSps = 1.5, pitchDirection = "rising", hnr = 12.0
            )
        )
        val baseline = ProsodicAnalyzer.Baseline(
            f0Mean = 180.0, energyMean = 0.05, rateMean = 3.2, samples = 10
        )
        val context = ProsodicAnalyzer.buildProsodicContext(features, baseline)
        assertTrue(context.contains("PERSONAL BASELINE"))
        assertTrue(context.contains("below-personal-baseline"))
        assertTrue(context.contains("above this speaker's baseline"))
    }
}
