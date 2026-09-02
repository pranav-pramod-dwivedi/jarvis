package com.pr4nav.jarvis

import com.pr4nav.jarvis.router.JarvisRouter
import com.pr4nav.jarvis.router.TaskCategory
import org.junit.Assert.*
import org.junit.Test

class JarvisRouterClassificationTest {

    @Test
    fun testClassifiesDeviceCommands() {
        val q1 = JarvisRouter.classify("Turn on the flashlight")
        assertEquals(TaskCategory.DEVICE_COMMAND, q1.category)

        val q2 = JarvisRouter.classify("Turn Bluetooth on")
        assertEquals(TaskCategory.DEVICE_COMMAND, q2.category)

        val q3 = JarvisRouter.classify("Volume 80%")
        assertEquals(TaskCategory.DEVICE_COMMAND, q3.category)

        val q4 = JarvisRouter.classify("Open Chrome")
        assertEquals(TaskCategory.DEVICE_COMMAND, q4.category)

        val q5 = JarvisRouter.classify("Take a screenshot")
        assertEquals(TaskCategory.DEVICE_COMMAND, q5.category)
    }

    @Test
    fun testClassifiesCasualQueries() {
        val q1 = JarvisRouter.classify("Hi")
        assertEquals(TaskCategory.CASUAL, q1.category)

        val q2 = JarvisRouter.classify("How are you?")
        assertEquals(TaskCategory.CASUAL, q2.category)

        val q3 = JarvisRouter.classify("Tell me a joke")
        assertEquals(TaskCategory.CASUAL, q3.category)

        val q4 = JarvisRouter.classify("Thank you")
        assertEquals(TaskCategory.CASUAL, q4.category)
    }

    @Test
    fun testClassifiesGeneralKnowledge() {
        val q1 = JarvisRouter.classify("What is photosynthesis?")
        assertEquals(TaskCategory.GENERAL_KNOWLEDGE, q1.category)

        val q2 = JarvisRouter.classify("Who was Albert Einstein?")
        assertEquals(TaskCategory.GENERAL_KNOWLEDGE, q2.category)

        val q3 = JarvisRouter.classify("Explain quantum computing")
        assertEquals(TaskCategory.GENERAL_KNOWLEDGE, q3.category)
    }

    @Test
    fun testClassifiesCodingTasks() {
        val q1 = JarvisRouter.classify("Write a python script to parse json")
        assertEquals(TaskCategory.CODING, q1.category)

        val q2 = JarvisRouter.classify("Fix this kotlin function syntax error")
        assertEquals(TaskCategory.CODING, q2.category)

        val q3 = JarvisRouter.classify("Refactor the database helper class")
        assertEquals(TaskCategory.CODING, q3.category)
    }

    @Test
    fun testClassifiesComplexReasoning() {
        val q1 = JarvisRouter.classify("Plan step by step how to migrate the database")
        assertEquals(TaskCategory.COMPLEX_REASONING, q1.category)

        val q2 = JarvisRouter.classify("Evaluate architecture and compare pros and cons")
        assertEquals(TaskCategory.COMPLEX_REASONING, q2.category)
    }

    @Test
    fun testLowConfidenceDetector() {
        assertTrue(JarvisRouter.isLowConfidenceAnswer("I am not sure about the exact date."))
        assertTrue(JarvisRouter.isLowConfidenceAnswer("I don't know the answer to this."))
        assertTrue(JarvisRouter.isLowConfidenceAnswer("Too short."))
        assertFalse(JarvisRouter.isLowConfidenceAnswer("Photosynthesis is the biological process where plants convert light into chemical energy."))
    }
}
