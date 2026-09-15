package com.pr4nav.jarvis.probe

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pr4nav.jarvis.router.RouteEngine
import com.pr4nav.jarvis.router.UnifiedAssistantDispatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end turns through the dispatcher on the offline route.
 * Proves a turn ALWAYS terminates with a message — never spins forever.
 * Restores the user's route afterwards.
 */
@RunWith(AndroidJUnit4::class)
class LocalRouteE2ETest {

    @Test fun offlineTurnTerminatesWithMessage() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = UnifiedAssistantDispatcher.getRoute(ctx)
        UnifiedAssistantDispatcher.setRoute(ctx, listOf(RouteEngine.LOCAL))
        try {
            runTurn(ctx, "hi", "JARVIS")
            // Nonsense misses deterministic matching → exercises the LOCAL engine itself.
            runTurn(ctx, "blorple wibble zorp", "offline")
            // Read-only device action → proves offline TOOL execution end to end.
            runTurn(ctx, "what is the battery percentage", "batter")
        } finally {
            UnifiedAssistantDispatcher.setRoute(ctx, previous)
        }
    }

    private fun runTurn(ctx: Context, query: String, expectContains: String) {
        val latch = CountDownLatch(1)
        var text = ""
        var handled = false
        val t0 = System.nanoTime()

        UnifiedAssistantDispatcher.execute(
            context = ctx,
            rawQuery = query,
            onStatus = null,
            onChunk = null,
            onEvent = null,
            onResult = { res ->
                text = res.fullSummary
                handled = res.handled
                latch.countDown()
            }
        )

        assertTrue("turn [$query] never finished", latch.await(30, TimeUnit.SECONDS))
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("PROBE local_turn_ms=$ms handled=$handled query=$query text=$text")
        assertTrue(text.isNotBlank())
        assertTrue(
            "turn [$query] answer missing [$expectContains]: $text",
            text.contains(expectContains, ignoreCase = true)
        )
    }
}
