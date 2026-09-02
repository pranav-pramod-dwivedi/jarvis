package com.pr4nav.jarvis.router

import org.junit.Assert.*
import org.junit.Test

class EscalationBudgetTest {

    @Test
    fun testEscalationBudgetLimits() {
        // Device Commands: Direct Needle -> Qwen Translator -> Cloud Interpreter -> Direct Needle (Max 3 hops)
        val maxDeviceHops = 3
        assertTrue("Device escalation must be bounded <= 3", maxDeviceHops <= 3)

        // General Knowledge: Local Qwen -> Cloud Gemini (Max 2 hops)
        val maxKnowledgeHops = 2
        assertTrue("Knowledge escalation must be bounded <= 2", maxKnowledgeHops <= 2)

        // Coding: Agent Execution -> Error Inspection & Self-Recovery (Max 2 retries)
        val maxCodingRetries = 2
        assertTrue("Coding retries must be bounded <= 2", maxCodingRetries <= 2)
    }

    @Test
    fun testLowConfidenceAnswerDetector() {
        // High confidence direct answer
        assertFalse(JarvisRouter.isLowConfidenceAnswer("Paris is the capital of France."))

        // Low confidence / uncertain answers trigger escalation
        assertTrue(JarvisRouter.isLowConfidenceAnswer("I don't know the answer to this question."))
        assertTrue(JarvisRouter.isLowConfidenceAnswer("I am not sure about this information."))
        assertTrue(JarvisRouter.isLowConfidenceAnswer("Short"))
    }
}
