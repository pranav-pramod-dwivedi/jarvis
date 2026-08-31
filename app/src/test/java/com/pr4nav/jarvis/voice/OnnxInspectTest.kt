package com.pr4nav.jarvis.voice

import ai.onnxruntime.OrtEnvironment
import org.junit.Test

class OnnxInspectTest {
    @Test
    fun inspectModels() {
        val env = OrtEnvironment.getEnvironment()
        val mel = env.createSession("src/main/assets/melspectrogram.onnx")
        println("MEL INPUTS: " + mel.inputNames + " -> " + mel.inputInfo)
        println("MEL OUTPUTS: " + mel.outputNames + " -> " + mel.outputInfo)

        val emb = env.createSession("src/main/assets/embedding_model.onnx")
        println("EMB INPUTS: " + emb.inputNames + " -> " + emb.inputInfo)
        println("EMB OUTPUTS: " + emb.outputNames + " -> " + emb.outputInfo)

        val jarvis = env.createSession("src/main/assets/hey_jarvis_v0.1.onnx")
        println("JARVIS INPUTS: " + jarvis.inputNames + " -> " + jarvis.inputInfo)
        println("JARVIS OUTPUTS: " + jarvis.outputNames + " -> " + jarvis.outputInfo)
    }
}
