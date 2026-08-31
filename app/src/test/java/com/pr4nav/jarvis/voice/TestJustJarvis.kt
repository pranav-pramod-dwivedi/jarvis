package com.pr4nav.jarvis.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TestJustJarvis {

    @Test
    fun testJustJarvisClips() {
        val env = OrtEnvironment.getEnvironment()
        val mel = env.createSession("src/main/assets/melspectrogram.onnx")
        val emb = env.createSession("src/main/assets/embedding_model.onnx")
        val jarvis = env.createSession("src/main/assets/hey_jarvis_v0.1.onnx")

        for (filename in listOf("just_jarvis.wav", "sam_jarvis.wav")) {
            val wavFile = File(filename)
            val bytes = wavFile.readBytes()
            val pcmRaw = ShortArray((bytes.size - 44) / 2)
            ByteBuffer.wrap(bytes, 44, bytes.size - 44)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(pcmRaw)

            val pad = 16000 * 2
            val pcmData = ShortArray(pad + pcmRaw.size + pad)
            System.arraycopy(pcmRaw, 0, pcmData, pad, pcmRaw.size)

            val melBuffer = ArrayList<FloatArray>()
            for (i in 0 until 76) melBuffer.add(FloatArray(32) { 1.0f })
            val embBuffer = ArrayList<FloatArray>()
            for (i in 0 until 16) embBuffer.add(FloatArray(96) { 0.0f })
            val rawBuffer = ArrayList<Float>()
            for (i in 0 until 480) rawBuffer.add(0.0f)

            var maxProb = 0.0f
            for (i in 0 until pcmData.size step 1280) {
                val end = minOf(pcmData.size, i + 1280)
                for (j in i until end) rawBuffer.add(pcmData[j].toFloat())
                if (end - i < 1280) continue

                val input1760 = FloatArray(1760)
                val offset = rawBuffer.size - 1760
                for (k in 0 until 1760) input1760[k] = rawBuffer[offset + k]

                val audioTensor = OnnxTensor.createTensor(env, arrayOf(input1760))
                val melRes = mel.run(mapOf("input" to audioTensor))
                val melRaw = melRes[0].value as Array<Array<Array<FloatArray>>>
                val steps = melRaw[0][0].size
                for (t in 0 until steps) {
                    melBuffer.add(FloatArray(32) { b -> melRaw[0][0][t][b] / 10.0f + 2.0f })
                    while (melBuffer.size > 76) melBuffer.removeAt(0)
                }
                audioTensor.close()
                melRes.close()

                val embInput = Array(1) { Array(76) { f -> Array(32) { b -> FloatArray(1) { melBuffer[f][b] } } } }
                val embTensor = OnnxTensor.createTensor(env, embInput)
                val embRes = emb.run(mapOf("input_1" to embTensor))
                val embRaw = embRes[0].value as Array<Array<Array<FloatArray>>>
                embBuffer.add(embRaw[0][0][0].clone())
                while (embBuffer.size > 16) embBuffer.removeAt(0)
                embTensor.close()
                embRes.close()

                val jarvisInput = Array(1) { Array(16) { e -> FloatArray(96) { d -> embBuffer[e][d] } } }
                val jarvisTensor = OnnxTensor.createTensor(env, jarvisInput)
                val jarvisRes = jarvis.run(mapOf("x.1" to jarvisTensor))
                val probRaw = jarvisRes[0].value as Array<FloatArray>
                val prob = probRaw[0][0]
                jarvisTensor.close()
                jarvisRes.close()

                if (prob > maxProb) maxProb = prob
            }
            println("File $filename -> Max Probability = $maxProb")
        }

        mel.close(); emb.close(); jarvis.close(); env.close()
    }
}
