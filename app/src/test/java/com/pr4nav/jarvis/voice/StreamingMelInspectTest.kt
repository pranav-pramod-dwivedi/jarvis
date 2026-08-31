package com.pr4nav.jarvis.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import org.junit.Test
import java.io.File
import kotlin.math.sin

class StreamingMelInspectTest {
    @Test
    fun testStreamingMel() {
        val env = OrtEnvironment.getEnvironment()
        val melSession = env.createSession("src/main/assets/melspectrogram.onnx")

        // openWakeWord: x is 16-bit PCM amplitude as float32! e.g. -32768.0 to 32767.0
        // Window is n_samples + 160*3 = 1280 + 480 = 1760 samples!
        val dummyAudioInt16AsFloat = FloatArray(1760) { (sin(it.toDouble() * 0.1) * 10000.0).toFloat() }
        val audioTensor = OnnxTensor.createTensor(env, arrayOf(dummyAudioInt16AsFloat))
        val melResult = melSession.run(mapOf("input" to audioTensor))
        val melRaw = melResult[0].value as Array<Array<Array<FloatArray>>>

        val batch = melRaw.size
        val dim1 = melRaw[0].size
        val timeSteps = melRaw[0][0].size
        val melBins = melRaw[0][0][0].size
        println("Mel output with 1760 samples: batch=$batch, dim1=$dim1, timeSteps=$timeSteps, melBins=$melBins")
        println("Raw mel sample value: ${melRaw[0][0][0][0]}")
        val transformed = melRaw[0][0][0][0] / 10.0f + 2.0f
        println("Transformed mel sample value (/10 + 2): $transformed")

        audioTensor.close()
        melResult.close()
        melSession.close()
        env.close()
    }
}
