package com.pr4nav.jarvis

import android.content.ContextWrapper
import android.content.Intent
import com.pr4nav.jarvis.chat.AgentStreamEvent
import com.pr4nav.jarvis.response.AnswerSynthesizer
import com.pr4nav.jarvis.response.ResponseMode
import com.pr4nav.jarvis.router.LocalEngine
import com.pr4nav.jarvis.router.UnifiedAssistantDispatcher
import com.pr4nav.jarvis.router.UnifiedExecutionResult
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolResult
import com.pr4nav.jarvis.tools.ToolStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ActionOutcomeTest {
    private val context = ContextWrapper(null)

    @Test fun failuresCannotBecomeSuccessProseRegardlessOfQueryOrMode() {
        val failures = listOf(
            ToolResult.notFound("YouTube"), ToolResult.permissionDenied("launch"),
            ToolResult.timeout(20), ToolResult.failure("LAUNCH_ERROR", "Activity blocked"),
            ToolResult.requiresUser("search", "Select a video to play; playback is not confirmed.")
        )
        for (failure in failures) {
            for ((query, tool) in listOf("open YouTube" to "open_app", "play news today" to "media.play")) {
                for (mode in ResponseMode.values()) {
                    val text = AnswerSynthesizer.synthesize(query, tool, failure, mode)
                    assertTrue("Lost error: $text", text.contains(failure.error!!.message))
                    assertFalse(text.contains("Task completed successfully"))
                    assertFalse(text.startsWith("Opening"))
                }
            }
        }
    }

    @Test fun successfulToolMessageWinsOverQueryKeywordsAndDefaultState() {
        val result = ToolResult.ok(JSONObject().put("message", "Flashlight turned off.").put("state", "off"))
        assertEquals("Flashlight turned off.", AnswerSynthesizer.synthesize(
            "turn off flashlight", "system.torch", result, ResponseMode.ACTION
        ))
    }

    @Test fun missingSuccessfulPayloadDoesNotInventConfirmation() {
        val answer = AnswerSynthesizer.synthesize("open YouTube", "open_app", ToolResult.ok(), ResponseMode.ACTION)
        assertTrue(answer.contains("no confirmation", ignoreCase = true))
    }

    private fun withFailedLaunch(block: () -> Unit) {
        CanonicalToolRegistry.init(context)
        val original = CanonicalToolRegistry.get("open_app")!!
        CanonicalToolRegistry.register(original.copy(execute = { _, _ -> ToolResult.notFound("YouTube") }))
        try { block() } finally { CanonicalToolRegistry.register(original) }
    }

    @Test fun dispatcherPropagatesFailedLaunchToTextFinalAndResult() = withFailedLaunch {
        val events = mutableListOf<AgentStreamEvent>()
        val done = CountDownLatch(1)
        var result: UnifiedExecutionResult? = null
        UnifiedAssistantDispatcher.execute(context, "open YouTube", onEvent = { events.add(it) }) {
            result = it
            done.countDown()
        }
        assertTrue("Dispatcher did not finish", done.await(3, TimeUnit.SECONDS))
        assertFalse(result!!.handled)
        assertTrue(result!!.jarvisResponse.text.contains("Item not found: YouTube"))
        assertFalse(events.filterIsInstance<AgentStreamEvent.Final>().single().handled)
        assertFalse(events.filterIsInstance<AgentStreamEvent.ToolEnd>().single().verified)
    }

    @Test fun offlineRouteCannotSayOpeningWhenLaunchFailed() = withFailedLaunch {
        val events = mutableListOf<AgentStreamEvent>()
        val result = LocalEngine.run(context, "open YouTube", System.currentTimeMillis()) { events.add(it) }
        assertFalse(result.handled)
        assertTrue(result.jarvisResponse.text.contains("Item not found: YouTube"))
        assertFalse(result.jarvisResponse.text.startsWith("Opening"))
    }

    @Test fun rejectedBluetoothContinuationIsNotMarkedSuccessful() {
        CanonicalToolRegistry.init(context)
        val original = CanonicalToolRegistry.get("system.bluetooth")!!
        com.pr4nav.jarvis.context.ContextManager.clear()
        CanonicalToolRegistry.register(original.copy(execute = { _, _ -> ToolResult.permissionDenied("Bluetooth") }))
        try {
            com.pr4nav.jarvis.context.ContextManager.updateToolContext("system.bluetooth", JSONObject().put("state", true), "bluetooth")
            var result: UnifiedExecutionResult? = null
            UnifiedAssistantDispatcher.execute(context, "turn it off") { result = it }
            assertNotNull(result)
            assertFalse(result!!.handled)
            assertEquals("Permission required: Bluetooth", result!!.speechResponse)
        } finally {
            CanonicalToolRegistry.register(original)
            com.pr4nav.jarvis.context.ContextManager.clear()
        }
    }

    @Test fun legacyRouterAlsoPreservesFailedToolResult() = withFailedLaunch {
        val done = CountDownLatch(1)
        var reply = ""
        var handled = true
        com.pr4nav.jarvis.router.JarvisRouter.route(context, "open YouTube") {
            reply = it.responseText
            handled = it.handled
            done.countDown()
        }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertFalse(handled)
        assertTrue(reply.contains("Item not found: YouTube"))
    }

    @Test fun friendlyLaunchAliasesDoNotDiscardUnderlyingFailure() = withFailedLaunch {
        for (name in listOf("app_launch_friendly", "music_open_spotify")) {
            val result = CanonicalToolRegistry.get(name)!!.execute(context, JSONObject().put("app", "YouTube"))
            assertFalse("$name discarded failure", result.success)
            assertEquals(ToolStatus.NOT_FOUND, result.status)
            assertEquals("Item not found: YouTube", result.error!!.message)
        }
    }

    @Test fun videoRequestRetainsItsQueryInsteadOfOnlyOpeningYouTube() {
        val match = com.pr4nav.jarvis.router.LanguageNormalizer.normalize("play instrumental music on YouTube")!!
        assertEquals("media.play", match.tool)
        assertEquals("instrumental music", match.args.getString("query"))
        assertEquals("youtube", match.args.getString("provider"))
        CanonicalToolRegistry.init(context)
        assertTrue(com.pr4nav.jarvis.tools.ToolValidator.validate(context, match.tool, match.args,
            "play instrumental music on YouTube") is com.pr4nav.jarvis.tools.ValidationResult.Valid)
    }

    @Test fun mediaProviderAndQueryAreKeptSeparateAndEncoded() {
        val plan = com.pr4nav.jarvis.tools.MediaPlayback.plan("AC/DC & live on YouTube", "default")
        assertEquals("youtube", plan.provider)
        assertEquals("AC/DC & live", plan.query)
        assertEquals("https://www.youtube.com/results?search_query=AC%2FDC%20%26%20live", plan.uri)
        assertEquals("spotify:search:hello%20world", com.pr4nav.jarvis.tools.MediaPlayback.plan("hello world", "SPOTIFY").uri)
        assertNull(com.pr4nav.jarvis.tools.MediaPlayback.plan("hello", "default").uri)
    }

    @Test fun unsupportedMediaProviderFailsBeforeLaunch() {
        val ctx = LaunchContext(false)
        val result = com.pr4nav.jarvis.tools.MediaPlayback.play(ctx,
            JSONObject().put("query", "hello").put("provider", "not-a-provider"))
        assertFalse(result.success)
        assertEquals(0, ctx.launches)
        assertEquals("INVALID_ARGUMENTS", result.error!!.code)
    }

    private class LaunchContext(private val deny: Boolean) : ContextWrapper(null) {
        var launches = 0
        override fun startActivity(intent: Intent) {
            launches++
            if (deny) throw SecurityException("Launch blocked by Android")
        }
    }

    @Test fun mediaToolsMustActuallyAttemptLaunchAndPropagateRejection() {
        CanonicalToolRegistry.init(context)
        for (name in listOf("media.play", "music_play")) {
            val ctx = LaunchContext(true)
            val result = CanonicalToolRegistry.get(name)!!.execute(ctx,
                JSONObject().put("query", "a video").put("provider", "youtube"))
            assertEquals("$name never launched anything", 1, ctx.launches)
            assertFalse("$name hid launch rejection", result.success)
            assertTrue(result.error!!.message.contains("Launch blocked by Android"))
        }
    }

    @Test fun acceptedMediaIntentIsNotConfirmedPlayback() {
        CanonicalToolRegistry.init(context)
        for (name in listOf("media.play", "music_play")) {
            val ctx = LaunchContext(false)
            val result = CanonicalToolRegistry.get(name)!!.execute(ctx,
                JSONObject().put("query", "a video").put("provider", "youtube"))
            assertEquals(1, ctx.launches)
            assertFalse(result.success)
            assertEquals(ToolStatus.ACTION_REQUIRES_USER, result.status)
            assertTrue(result.error!!.message.contains("playback", ignoreCase = true))
            assertTrue(result.error!!.message.contains("not confirmed"))
        }
    }

    @Test fun emptyMediaQueryCannotReportPlaying() {
        CanonicalToolRegistry.init(context)
        for (name in listOf("media.play", "music_play")) {
            val ctx = LaunchContext(false)
            val result = CanonicalToolRegistry.get(name)!!.execute(ctx, JSONObject().put("query", " "))
            assertEquals(0, ctx.launches)
            assertFalse(result.success)
            assertEquals("INVALID_ARGUMENTS", result.error!!.code)
        }
    }
}
