package com.pr4nav.jarvis.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class OpenWakeWordSpecTest {

    @Test
    fun testAccurateOpenWakeWordPipeline() {
        val env = OrtEnvironment.getEnvironment()
        val mel = env.createSession("src/main/assets/melspectrogram.onnx")
        val emb = env.createSession("src/main/assets/embedding_model.onnx")
        val jarvis = env.createSession("src/main/assets/hey_jarvis_v0.1.onnx")

        // 1. Raw 16-bit PCM as FloatArray directly (range ~ -32768 to 32767)
        // openWakeWord uses 1760 samples (1280 new + 480 history) for streaming mel
        val pcmSamples = FloatArray(1760) { (sin(it.toDouble() * 0.05) * 8000.0).toFloat() }
        val audioTensor = OnnxTensor.createTensor(env, arrayOf(pcmSamples))
        val melRes = mel.run(mapOf("input" to audioTensor))
        val melRaw = melRes[0].value as Array<Array<Array<FloatArray>>>
        val steps = melRaw[0][0].size
        assertEquals(8, steps)

        // 2. Transform: spec / 10 + 2
        val melFrames = Array(steps) { t ->
            FloatArray(32) { b -> melRaw[0][0][t][b] / 10.0f + 2.0f }
        }

        // 3. Rolling mel buffer of 76 frames
        val melBuffer = Array(76) { f ->
            Array(32) { b ->
                FloatArray(1) { melFrames[f % steps][b] }
            }
        }

        val embTensor = OnnxTensor.createTensor(env, arrayOf(melBuffer))
        val embRes = emb.run(mapOf("input_1" to embTensor))
        val embRaw = embRes[0].value as Array<Array<Array<FloatArray>>>
        val embedding = embRaw[0][0][0]
        assertEquals(96, embedding.size)

        // 4. Rolling embedding buffer of 16 vectors
        val embBuffer = Array(16) { embedding }
        val jarvisTensor = OnnxTensor.createTensor(env, arrayOf(embBuffer))
        val jarvisRes = jarvis.run(mapOf("x.1" to jarvisTensor))
        val probRaw = jarvisRes[0].value as Array<FloatArray>
        val prob = probRaw[0][0]
        println("Calculated probability for tone with accurate openWakeWord specs: $prob")
        assertTrue(prob in 0.0f..1.0f)

        audioTensor.close(); melRes.close()
        embTensor.close(); embRes.close()
        jarvisTensor.close(); jarvisRes.close()
        mel.close(); emb.close(); jarvis.close(); env.close()
    }
}
