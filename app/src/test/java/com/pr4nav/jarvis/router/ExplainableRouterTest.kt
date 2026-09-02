package com.pr4nav.jarvis.router

import android.content.ContextWrapper
import com.pr4nav.jarvis.router.JarvisRouter.JarvisRoutingResult
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.workspace.JarvisWorkspace
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExplainableRouterTest {

    @Before
    fun setup() {
        JarvisWorkspace.initWorkspace(null)
        CanonicalToolRegistry.init(null)
    }

    @Test
    fun testExplainableDirectToolRoute() {
        val context = ContextWrapper(null)
        val latch = CountDownLatch(1)
        var result: JarvisRoutingResult? = null

        JarvisRouter.route(
            context = context,
            input = "Turn on Bluetooth",
            onResult = { res ->
                result = res
                latch.countDown()
            }
        )

        latch.await(5, TimeUnit.SECONDS)
        assertNotNull(result)
        val res = result!!

        assertEquals("DIRECT_EXISTING_TOOL", res.routeSelected)
        assertTrue("Confidence must be high for direct tools", res.confidence >= 0.90f)
        assertTrue("Thinking trace must contain route explanation", res.thinkingTrace.contains("Router:") || res.thinkingTrace.contains("Direct"))
        assertNotNull("Model engine must be identified", res.modelEngine)
    }

    @Test
    fun testExplainableCasualRoute() {
        val classification = JarvisRouter.classify("Hello, good morning!")
        assertEquals(TaskCategory.CASUAL, classification.category)
        assertTrue(classification.confidence >= 0.90f)
        assertTrue(classification.reasoning.isNotBlank())
    }

    @Test
    fun testExplainableCodingRoute() {
        val classification = JarvisRouter.classify("Write a python script to parse json files")
        assertEquals(TaskCategory.CODING, classification.category)
        assertTrue(classification.confidence >= 0.90f)
        assertTrue(classification.reasoning.isNotBlank())
    }
}
