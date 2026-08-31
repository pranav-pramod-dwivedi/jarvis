package com.pr4nav.jarvis.voice

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnnxWakeWordEngineProbeTest {

    @Test
    fun testOnnxModelsLoadAndInspectShapes() {
        val env = OrtEnvironment.getEnvironment()
        val jarvisFile = File("src/main/assets/hey_jarvis_v0.1.onnx")
        val melFile = File("src/main/assets/melspectrogram.onnx")
        val embFile = File("src/main/assets/embedding_model.onnx")

        assertTrue("hey_jarvis_v0.1.onnx must exist", jarvisFile.exists())
        assertTrue("melspectrogram.onnx must exist", melFile.exists())
        assertTrue("embedding_model.onnx must exist", embFile.exists())

        val melSession = env.createSession(melFile.absolutePath)
        println("=== MELSPECTROGRAM MODEL ===")
        for (name in melSession.inputNames) {
            println("Input: $name -> ${melSession.inputInfo[name]?.info}")
        }
        for (name in melSession.outputNames) {
            println("Output: $name -> ${melSession.outputInfo[name]?.info}")
        }

        val embSession = env.createSession(embFile.absolutePath)
        println("=== EMBEDDING MODEL ===")
        for (name in embSession.inputNames) {
            println("Input: $name -> ${embSession.inputInfo[name]?.info}")
        }
        for (name in embSession.outputNames) {
            println("Output: $name -> ${embSession.outputInfo[name]?.info}")
        }

        val jarvisSession = env.createSession(jarvisFile.absolutePath)
        println("=== HEY JARVIS MODEL ===")
        for (name in jarvisSession.inputNames) {
            println("Input: $name -> ${jarvisSession.inputInfo[name]?.info}")
        }
        for (name in jarvisSession.outputNames) {
            println("Output: $name -> ${jarvisSession.outputInfo[name]?.info}")
        }

        melSession.close()
        embSession.close()
        jarvisSession.close()
        env.close()
    }
}
