package com.wim.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The log file must drop Android's overlay drawing chatter and keep every WiM line. */
class WimLogFileNoiseTest {

    @Test fun drawingChatterIsNoise() {
        assertTrue(WimLogFile.isSystemNoise("09-29 10:23:00.534  1022  1482 D VRI[]@2735eb3: Setup new sync=wmsSync-VRI"))
        assertTrue(WimLogFile.isSystemNoise("09-29 10:23:00.534  1022  1482 D BLASTBufferQueue_Java: update, w= 1080 h= 2520"))
        assertTrue(WimLogFile.isSystemNoise("09-29 10:23:00.534  1022  1482 D InsetsController: onStateChanged"))
    }

    @Test fun wimLinesAreKept() {
        assertFalse(WimLogFile.isSystemNoise("09-29 10:23:25.961  1022 22266 I WiM     : Whisper (whisper-1 via direct, 2036ms): hello"))
        assertFalse(WimLogFile.isSystemNoise("09-29 10:23:23.915  1022 22266 I WavSilenceTrimmer: Trimmed 742444 → 741164 bytes"))
        assertFalse(WimLogFile.isSystemNoise("09-29 00:01:20.417  1022  1482 W Firestore: (25.1.1) [WatchStream]: Stream closed"))
        assertFalse(WimLogFile.isSystemNoise("---- WimLogFile start pid=1022 ----"))
    }
}
