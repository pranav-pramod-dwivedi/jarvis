package com.pr4nav.jarvis.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer

class OnnxEndToEndPipelineTest {

    @Test
    fun testPipelineInference() {
        val env = OrtEnvironment.getEnvironment()
        val melSession = env.createSession("src/main/assets/melspectrogram.onnx")
        val embSession = env.createSession("src/main/assets/embedding_model.onnx")
        val jarvisSession = env.createSession("src/main/assets/hey_jarvis_v0.1.onnx")

        // 1. Feed 1280 samples (80ms at 16kHz)
        val dummyAudio = FloatArray(1280) { 0.01f }
        val audioTensor = OnnxTensor.createTensor(env, arrayOf(dummyAudio))

        val melResult = melSession.run(mapOf("input" to audioTensor))
        val melOutput = melResult[0].value as Array<Array<Array<FloatArray>>>
        println("Mel output shape: [${melOutput.size}][${melOutput[0].size}][${melOutput[0][0].size}][${melOutput[0][0][0].size}]")

        // 2. Prepare 76x32x1 input for embedding model
        val embInput = Array(1) { Array(76) { Array(32) { FloatArray(1) { 0.1f } } } }
        val embTensor = OnnxTensor.createTensor(env, embInput)
        val embResult = embSession.run(mapOf("input_1" to embTensor))
        val embOutput = embResult[0].value as Array<Array<Array<FloatArray>>>
        println("Emb output shape: [${embOutput.size}][${embOutput[0].size}][${embOutput[0][0].size}][${embOutput[0][0][0].size}]")
        assertEquals(96, embOutput[0][0][0].size)

        // 3. Prepare 1x16x96 input for hey_jarvis model
        val jarvisInput = Array(1) { Array(16) { FloatArray(96) { embOutput[0][0][0][it] } } }
        val jarvisTensor = OnnxTensor.createTensor(env, jarvisInput)
        val jarvisResult = jarvisSession.run(mapOf("x.1" to jarvisTensor))
        val jarvisProb = jarvisResult[0].value as Array<FloatArray>
        println("Hey Jarvis probability output: ${jarvisProb[0][0]}")
        assertTrue(jarvisProb[0][0] in 0.0f..1.0f)

        audioTensor.close()
        melResult.close()
        embTensor.close()
        embResult.close()
        jarvisTensor.close()
        jarvisResult.close()
        melSession.close()
        embSession.close()
        jarvisSession.close()
        env.close()
    }
}
