package com.wim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AudioCompressorPathTest {
    @Test fun parallelCallsReceiveDifferentOutputPaths() {
        val dir = createTempDirectory("wim-audio-path-").toFile()
        try {
            val wav = File(dir, "wim_recording.wav")
            val first = AudioCompressor.newOutputFile(wav)
            val second = AudioCompressor.newOutputFile(wav)
            assertNotEquals(first.absolutePath, second.absolutePath)
            assertEquals(dir.absolutePath, first.parentFile?.absolutePath)
            assertFalse(first.exists())
            assertFalse(second.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
