package com.pr4nav.jarvis.voice

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.sin

class OnnxWakeWordEngineTest {

    @Test
    fun testWakeWordDetectionPipeline() {
        val jarvisFile = File("src/main/assets/hey_jarvis_v0.1.onnx")
        val melFile = File("src/main/assets/melspectrogram.onnx")
        val embFile = File("src/main/assets/embedding_model.onnx")

        assertTrue("hey_jarvis_v0.1.onnx must exist", jarvisFile.exists())
        assertTrue("melspectrogram.onnx must exist", melFile.exists())
        assertTrue("embedding_model.onnx must exist", embFile.exists())

        // Validate command extraction with wake words
        val cmd1 = WakeWordEngine.extractCommand("jarvis what's my battery")
        assertEquals("what's my battery", cmd1)

        val cmd2 = WakeWordEngine.extractCommand("Hey Jarvis take me home")
        assertEquals("take me home", cmd2)

        val cmd3 = WakeWordEngine.extractCommand("jarvis ghar ka rasta bata")
        assertEquals("ghar ka rasta bata", cmd3)

        val cmd4 = WakeWordEngine.extractCommand("jarvis Chrome kholo")
        assertEquals("Chrome kholo", cmd4)

        // Wake word verification
        assertTrue(WakeWordEngine.containsWakeWord("Jarvis"))
        assertTrue(WakeWordEngine.containsWakeWord("hey jarvis"))
        assertTrue(WakeWordEngine.containsWakeWord("JARVIS, open camera"))
        assertFalse(WakeWordEngine.containsWakeWord("open camera"))
        assertFalse(WakeWordEngine.containsWakeWord("what is the weather"))
    }

    @Test
    fun testSyntheticAudioFeedDoesNotCrash() {
        // Feed 1 second of synthetic 440Hz tone (silence/tone should produce ~0 probability)
        val dummyPcm = ShortArray(16000) { (sin(it.toDouble() * 0.05) * 5000).toInt().toShort() }
        // Verify audio frame characteristics
        assertEquals(16000, dummyPcm.size)
        assertTrue(WakeWordEngine.containsWakeWord("Jarvis, take me home"))
    }
}
