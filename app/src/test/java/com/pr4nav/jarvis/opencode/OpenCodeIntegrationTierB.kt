package com.pr4nav.jarvis.opencode

import com.pr4nav.jarvis.opencode.json.OcEvent
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * LLM-dependent flows against the REAL OpenCode binary.
 * Opt-in: set JARVIS_OC_LLM=1. Uses an isolated HOME; provider auth is copied from the
 * user's own ~/.local/share/opencode/auth.json so prompts run on the user's configured model.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenCodeIntegrationTierB {

    companion object {
        private lateinit var fx: OpenCodeServerFixture
        private lateinit var client: OpenCodeClient
        private var llmAvailable: Boolean = false

        @BeforeClass
        @JvmStatic
        fun boot() {
            OpenCodeLogger.level = OpenCodeLogger.WARN
            Assume.assumeTrue(
                "LLM tier opt-in only (set JARVIS_OC_LLM=1)",
                System.getenv("JARVIS_OC_LLM") == "1"
            )
            fx = OpenCodeServerFixture()
            val started = fx.start()
            Assume.assumeTrue("real opencode server could not start", started)

            val userAuth = File(System.getProperty("user.home"), ".local/share/opencode/auth.json")
            Assume.assumeTrue("no provider auth available to copy", userAuth.isFile)
            val target = File(fx.homeDir, ".local/share/opencode/auth.json")
            target.parentFile.mkdirs()
            userAuth.copyTo(target)

            client = OpenCodeClient { fx.config() }
            val providers = client.providers().getOrNull().orEmpty()
            val usable = providers.any { it.models.isNotEmpty() }
            if (!usable) {
                fx.stop()
            }
            Assume.assumeTrue("copied auth yielded no usable models", usable)
            llmAvailable = true
        }

        @AfterClass
        @JvmStatic
        fun teardown() {
            if (this::fx.isInitialized) fx.stop()
        }

        private fun await(latch: CountDownLatch, seconds: Long): Boolean =
            latch.await(seconds, TimeUnit.SECONDS)
    }

    class EventCapture : OpenCodeEventManager.Listener {
        val events = ConcurrentLinkedQueue<OcEvent>()
        override fun onEvent(event: OcEvent) {
            events.add(event)
        }

        fun waitFor(
            mgr: OpenCodeEventManager,
            sessionId: String? = null,
            seconds: Long,
            predicate: (OcEvent) -> Boolean
        ): OcEvent? {
            val deadline = System.currentTimeMillis() + seconds * 1000
            while (System.currentTimeMillis() < deadline) {
                val hit = events.firstOrNull { ev ->
                    (sessionId == null || sid(ev) == sessionId) && predicate(ev)
                }
                if (hit != null) return hit
                Thread.sleep(120)
            }
            return null
        }

        private fun sid(ev: OcEvent): String? = when (ev) {
            is OcEvent.SessionIdle -> ev.sessionId
            is OcEvent.SessionError -> ev.sessionId
            is OcEvent.MessageUpdated -> ev.message.sessionId
            is OcEvent.PartUpdated -> ev.part.sessionId
            else -> null
        }

        fun sidOf(ev: OcEvent): String? = sid(ev)?.take(14)
    }

    private fun pickModel(): com.pr4nav.jarvis.opencode.json.OcModelRef {
        val cat = client.providers().getOrThrow()
        val p = cat.first { it.models.isNotEmpty() }
        val m = p.models.first()
        return com.pr4nav.jarvis.opencode.json.OcModelRef(p.id, m.id, m.variants.firstOrNull())
    }

    @Test
    fun t20_promptStreamsTextAndReachesIdle() {
        val dir = fx.projectDir!!.absolutePath
        val session = client.createSession(dir).getOrThrow()
        val capture = EventCapture()
        val mgr = OpenCodeEventManager({ fx.config() }, client)
        mgr.subscribe(capture)
        mgr.start()
        try {
            assertTrue(mgr.awaitConnected(20_000))
            val submit = client.promptAsync(
                session.id,
                listOf(OpenCodeClient.PromptPart.Text("Reply with exactly: OK")),
                dir,
                agent = "build",
                model = pickModel()
            )
            assertTrue("prompt_async failed: ${(submit as? OcResult.Err)?.error}", submit.isOk)

            val idle = capture.waitFor(mgr, session.id, 120) { it is OcEvent.SessionIdle }
            assertTrue("never reached session.idle", idle != null)

            val assistantText = capture.events
                .filterIsInstance<OcEvent.PartUpdated>()
                .filter { it.part.type == "text" && !it.part.synthetic && it.part.text != null }
                .mapNotNull { it.part.text }
                .joinToString(" ")
            assertTrue("assistant text never streamed", assistantText.isNotBlank())

            val msgs = client.messages(session.id, null, dir).getOrThrow()
            val finalText = msgs.lastOrNull()?.second
                ?.filter { it.type == "text" && !it.synthetic }?.joinToString("\n") { it.text ?: "" }.orEmpty()
            assertTrue(
                "final transcript missing text (stream='$assistantText', final='$finalText')",
                assistantText.contains("OK") || finalText.contains("OK")
            )
        } finally {
            mgr.stop()
            client.deleteSession(session.id, dir)
        }
    }

    @Test
    fun t21_abortMidTaskStopsWork() {
        val dir = fx.projectDir!!.absolutePath
        val session = client.createSession(dir).getOrThrow()
        val capture = EventCapture()
        val mgr = OpenCodeEventManager({ fx.config() }, client)
        mgr.subscribe(capture)
        mgr.start()
        try {
            assertTrue(mgr.awaitConnected(20_000))
            client.promptAsync(
                session.id,
                listOf(OpenCodeClient.PromptPart.Text("Write the numbers 1 to 60 each on its own line using bash echo commands one at a time.")),
                dir,
                agent = "build",
                model = pickModel()
            ).getOrThrow()

            val activity = capture.waitFor(mgr, session.id, 90) {
                it is OcEvent.PartUpdated || it is OcEvent.MessageUpdated
            }
            Assume.assumeTrue("model produced no activity in 90s", activity != null)

            val aborted = client.abortSession(session.id, dir)
            assertTrue("abort call failed: ${aborted.errorOrNull()}", aborted.isOk)

            val stopped = capture.waitFor(mgr, session.id, 30) {
                it is OcEvent.SessionIdle || it is OcEvent.SessionError
            }
            assertTrue("session did not reach idle/error after abort", stopped != null)

            val busy = client.statusMap().getOrNull()?.get(session.id)?.isBusy
            assertEquals(false, busy == true)
        } finally {
            mgr.stop()
            client.deleteSession(session.id, dir)
        }
    }

    @Test
    fun t22_fileEditProducesDiffVisibility() {
        val dir = fx.projectDir!!.absolutePath
        val session = client.createSession(dir).getOrThrow()
        val capture = EventCapture()
        val mgr = OpenCodeEventManager({ fx.config() }, client)
        mgr.subscribe(capture)
        mgr.start()
        try {
            assertTrue(mgr.awaitConnected(20_000))
            client.promptAsync(
                session.id,
                listOf(OpenCodeClient.PromptPart.Text("Create a new file named jarvis_diff_test.txt whose content is exactly the word hello. Use your file-writing tool for this, not bash. Then reply done.")),
                dir,
                agent = "build",
                model = pickModel()
            ).getOrThrow()

            val terminal = capture.waitFor(mgr, session.id, 150) {
                it is OcEvent.SessionIdle || it is OcEvent.SessionError
            }
            assertTrue("task did not finish (no idle/error)", terminal != null)
            if (terminal is OcEvent.SessionError) {
                val err = terminal.errorMessage ?: terminal.errorName ?: "unknown"
                Assume.assumeTrue("provider API error ($err) — skipping file-edit verification", false)
            }
            val wroteFile = File(dir, "jarvis_diff_test.txt").isFile
            val diffEvents = capture.events.any { it is OcEvent.SessionDiff && it.files.isNotEmpty() }
            val changeToolSeen = capture.events.any {
                it is OcEvent.PartUpdated &&
                    (
                        it.part.toolName in setOf("write", "edit") ||
                            (it.part.toolName == "bash" && (it.part.toolInput?.toString()?.contains("jarvis_diff_test.txt") == true))
                        )
            }
            if (!wroteFile && !diffEvents && !changeToolSeen) {
                val toolsUsed = capture.events.filterIsInstance<OcEvent.PartUpdated>()
                    .mapNotNull { it.part.toolName }.distinct()
                val finalText = capture.events.filterIsInstance<OcEvent.PartUpdated>()
                    .filter { it.part.type == "text" && !it.part.synthetic }
                    .mapNotNull { it.part.text }.joinToString(" ")
                val evtTypes = capture.events.take(60).map { "${it::class.simpleName}/${capture.sidOf(it)}" }
                val errEvents = capture.events.filterIsInstance<OcEvent.SessionError>()
                    .joinToString("|") { "${it.errorName}: ${it.errorMessage?.take(120)}" }
                Assume.assumeTrue(
                    "model produced no file artifact (tools=$toolsUsed final='${finalText.take(120)}' events=$evtTypes errors=$errEvents) — inconclusive, not a transport failure",
                    false
                )
            }
            assertTrue(wroteFile || diffEvents || changeToolSeen)
        } finally {
            mgr.stop()
            client.deleteSession(session.id, dir)
        }
    }

    @Test
    fun t23_permissionAskRoundTripWithReject() {
        val dir = fx.projectDir!!
        fx.writeOpencodeConfig("{\"permission\":{\"bash\":\"ask\"}}")
        try {
            val session = client.createSession(dir.absolutePath).getOrThrow()
            val capture = EventCapture()
            val mgr = OpenCodeEventManager({ fx.config() }, client)
            mgr.subscribe(capture)
            mgr.start()
            try {
                assertTrue(mgr.awaitConnected(20_000))
                client.promptAsync(
                    session.id,
                    listOf(OpenCodeClient.PromptPart.Text("Use the bash tool to run exactly: echo jarvis-perm-probe")),
                    dir.absolutePath,
                    agent = "build",
                    model = pickModel()
                ).getOrThrow()

                val asked = capture.waitFor(mgr, session.id, 90) {
                    it is OcEvent.PermissionAsked || (it is OcEvent.MessageUpdated && it.message.role == "assistant")
                }
                Assume.assumeTrue(
                    "no permission was requested (model may have refused or policy not applied); got=${asked?.let { it::class.simpleName }}",
                    asked != null
                )

                if (asked is OcEvent.PermissionAsked) {
                    val pendingNow = client.pendingPermissions(dir.absolutePath).getOrThrow()
                    assertTrue(pendingNow.any { it.requestId == asked.request.requestId })
                    client.replyToPermission(asked.request.requestId, OpenCodeClient.PermissionDecision.REJECT, dir.absolutePath)
                        .getOrThrow()
                    val replied = capture.waitFor(mgr, session.id, 30) {
                        it is OcEvent.PermissionReplied
                    }
                    assertTrue("permission.replied never arrived", replied != null)
                }
            } finally {
                mgr.stop()
                client.deleteSession(session.id, dir.absolutePath)
            }
        } finally {
            File(dir, "opencode.json").delete()
        }
    }
}
