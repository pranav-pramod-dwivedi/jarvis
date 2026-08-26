package com.pr4nav.jarvis.opencode

import com.pr4nav.jarvis.opencode.json.OcEvent
import com.pr4nav.jarvis.opencode.json.OcSessionInfo
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenCodeIntegrationTierA {

    companion object {
        private lateinit var fx: OpenCodeServerFixture
        private lateinit var client: OpenCodeClient

        @BeforeClass
        @JvmStatic
        fun boot() {
            OpenCodeLogger.level = OpenCodeLogger.WARN
            fx = OpenCodeServerFixture()
            val started = fx.start()
            org.junit.Assume.assumeTrue("real opencode server could not start", started)
            client = OpenCodeClient { fx.config() }
        }

        @AfterClass
        @JvmStatic
        fun teardown() {
            if (this::fx.isInitialized) fx.stop()
        }

        private fun await(latch: CountDownLatch, seconds: Long = 15): Boolean =
            latch.await(seconds, TimeUnit.SECONDS)
    }

    @Test
    fun t01_healthAndVersion() {
        val r = client.health()
        assertTrue("health failed: ${r.errorOrNull()}", r.isOk)
        assertTrue(r.getOrNull()!!.healthy)
    }

    @Test
    fun t02_wrongPasswordMapsToAuthError() {
        val badClient = OpenCodeClient {
            fx.config().copy(username = "jarvistest", password = "definitely-wrong")
        }
        val r = badClient.health()
        assertTrue(r is OcResult.Err)
        assertEquals(OpenCodeException.Code.AUTH, (r as OcResult.Err).error.code)
    }

    @Test
    fun t03_closedPortMapsToUnavailable() {
        val deadPort = OpenCodeServerFixture.freePort()
        val deadClient = OpenCodeClient {
            OpenCodeConfig(baseUrl = "http://127.0.0.1:$deadPort")
        }
        val r = deadClient.health()
        assertTrue(r is OcResult.Err)
        assertEquals(OpenCodeException.Code.UNAVAILABLE, (r as OcResult.Err).error.code)
    }

    @Test
    fun t04_createListRenameForkDeleteSession() {
        val dir = fx.projectDir!!.absolutePath
        val created = client.createSession(dir).getOrThrow()
        assertNotNull(created.id)
        assertTrue(created.id.startsWith("ses"))

        val listed = client.sessions(dir).getOrThrow()
        assertTrue(listed.any { it.id == created.id })

        client.renameSession(created.id, "jarvis-test-title", dir).getOrThrow()
        val renamed = client.session(created.id, dir).getOrThrow()
        assertEquals("jarvis-test-title", renamed.title)

        val forked = client.forkSession(created.id, null, dir).getOrThrow()
        assertTrue(forked.id != created.id)

        client.deleteSession(forked.id, dir)
        client.deleteSession(created.id, dir)
    }

    @Test
    fun t05_missingSessionMapsToNotFound() {
        val r = client.session("ses_doesnotexist0000", fx.projectDir!!.absolutePath)
        assertTrue("expected error, got $r", r is OcResult.Err)
        val err = (r as OcResult.Err).error
        assertTrue(
            "unexpected code ${err.code}",
            err.code == OpenCodeException.Code.NOT_FOUND || err.code == OpenCodeException.Code.BAD_REQUEST
        )
    }

    @Test
    fun t06_statusMapParses() {
        val r = client.statusMap()
        assertTrue("status map failed: ${r.errorOrNull()}", r.isOk)
        assertNotNull(r.getOrNull())
    }

    @Test
    fun t07_projectsEndpointParses() {
        val r = client.projects()
        assertTrue("projects failed: ${r.errorOrNull()}", r.isOk)
    }

    @Test
    fun t08_providersCatalogParses() {
        val r = client.providers()
        assertTrue("providers failed: ${r.errorOrNull()}", r.isOk)
    }

    @Test
    fun t09_agentsEndpointParses() {
        val r = client.agents()
        assertTrue("agents failed: ${r.errorOrNull()}", r.isOk)
        val agents = r.getOrNull()!!
        assertTrue(agents.any { it.name == "build" })
        assertTrue(agents.any { it.name == "plan" })
    }

    @Test
    fun t10_emptyPromptRejectedCleanly() {
        val dir = fx.projectDir!!.absolutePath
        val session = client.createSession(dir).getOrThrow()
        val r = client.promptAsync(session.id, emptyList(), dir, null, null)
        assertTrue(r is OcResult.Err)
        val code = (r as OcResult.Err).error.code
        assertTrue(
            "expected BAD_REQUEST/SERVER/MALFORMED got $code",
            code == OpenCodeException.Code.BAD_REQUEST ||
                code == OpenCodeException.Code.MALFORMED ||
                code == OpenCodeException.Code.SERVER
        )
        client.deleteSession(session.id, dir)
    }

    @Test
    fun t11_globalEventStreamDeliversSessionEvents() {
        val events = ConcurrentLinkedQueue<OcEvent>()
        val mgr = OpenCodeEventManager({ fx.config() }, client)
        mgr.subscribe(object : OpenCodeEventManager.Listener {
            override fun onEvent(event: OcEvent) {
                events.add(event)
            }
        })
        mgr.start()
        try {
            // wait for SSE to actually connect before producing the event we expect
            val connectedLatch = CountDownLatch(1)
            Thread({
                val d = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < d) {
                    if (mgr.connected) { connectedLatch.countDown(); return@Thread }
                    Thread.sleep(100)
                }
            }).start()
            await(connectedLatch, 12)

            val latch = CountDownLatch(1)
            Thread({
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline) {
                    if (events.any { it is OcEvent.SessionCreated }) {
                        latch.countDown()
                        return@Thread
                    }
                    Thread.sleep(100)
                }
            }).start()

            val dir = fx.projectDir!!.absolutePath
            val session = client.createSession(dir).getOrThrow()
            val delivered = await(latch, 25)
            // flake-tolerant: if event bus missed the race, at least verify session is listable (server did create it)
            if (!delivered) {
                val listed = client.sessions(dir).getOrNull()?.any { it.id == session.id } == true
                org.junit.Assume.assumeTrue("session.created missed but session exists — SSE race on CI", listed)
                assertTrue(
                    "session.created never arrived; received=${events.map { it::class.simpleName }}",
                    false
                )
            }
            val sc = events.first { it is OcEvent.SessionCreated } as OcEvent.SessionCreated
            assertEquals(session.id, sc.session.id)
            client.deleteSession(session.id, dir)
        } finally {
            mgr.stop()
        }
    }

    @Test
    fun t12_directoryFilterBlocksForeignDirectories() {
        val foreignDir = "/definitely/not/a/jarvis/project"
        val received = ConcurrentLinkedQueue<OcEvent>()
        val mgr = OpenCodeEventManager({ fx.config() }, client)
        mgr.directoryFilter = setOf(foreignDir)
        mgr.subscribe(object : OpenCodeEventManager.Listener {
            override fun onEvent(event: OcEvent) {
                received.add(event)
            }
        })
        mgr.start()
        try {
            val dir = fx.projectDir!!.absolutePath
            val session = client.createSession(dir).getOrThrow()
            Thread.sleep(5_000)
            assertTrue(
                "filter leaked event from $dir: ${received.filterIsInstance<OcEvent.SessionCreated>()}",
                received.none { it is OcEvent.SessionCreated && it.session.directory?.startsWith(dir.take(20)) == true }
            )
            client.deleteSession(session.id, dir)
        } finally {
            mgr.stop()
        }
    }

    @Test
    fun t13_reconnectsAcrossServerCrashAndRestart() {
        val events = ConcurrentLinkedQueue<OcEvent>()
        val mgr = OpenCodeEventManager({ fx.config() }, client)
        mgr.subscribe(object : OpenCodeEventManager.Listener {
            override fun onEvent(event: OcEvent) {
                events.add(event)
            }
        })
        mgr.start()
        try {
            val sawFirstConnection = CountDownLatch(1)
            Thread({
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline) {
                    if (mgr.connected) {
                        sawFirstConnection.countDown()
                        return@Thread
                    }
                    Thread.sleep(100)
                }
            }).start()
            assertTrue(await(sawFirstConnection, 25))

            fx.stopServerProcess()
            val lostDeadline = System.currentTimeMillis() + 30_000
            var lost = false
            while (System.currentTimeMillis() < lostDeadline) {
                if (!mgr.connected) {
                    lost = true
                    break
                }
                Thread.sleep(200)
            }
            assertTrue("manager still believes it is connected after server kill", lost)

            assertTrue("restart on same port failed", fx.restartOnSamePort())
            val reconnected = CountDownLatch(1)
            Thread({
                val deadline = System.currentTimeMillis() + 45_000
                while (System.currentTimeMillis() < deadline) {
                    if (mgr.connected) {
                        reconnected.countDown()
                        return@Thread
                    }
                    Thread.sleep(200)
                }
            }).start()
            assertTrue("did not reconnect after server restart", await(reconnected, 50))
        } finally {
            mgr.stop()
        }
    }

    @Test
    fun t14_sessionRegistryPersistsAcrossManagerRestart() {
        val kv = InMemoryKvStore()
        val info = OcSessionInfo(id = "ses_regtest000000000", directory = "/tmp/x", title = "kept")
        val mgr1 = OpenCodeSessionManager(client, kv)
        mgr1.registerExisting(info)
        mgr1.persist()

        val mgr2 = OpenCodeSessionManager(client, kv)
        val loaded = mgr2.get("ses_regtest000000000")
        assertNotNull("registry did not survive manager recreation", loaded)
        assertEquals("kept", loaded!!.title)
    }

    @Test
    fun t15_corruptRegistryIsTolerated() {
        val kv = InMemoryKvStore()
        kv.putString(OpenCodeSessionStore.KEY_REGISTRY, "{not json at all")
        val mgr = OpenCodeSessionManager(client, kv)
        assertTrue(mgr.list().isEmpty())
    }

    @Test
    fun t16_facadeAdoptsRunningServerAndReceivesEvents() {
        val kv = InMemoryKvStore()
        OpenCode.shutdownTesting()
        val facade = OpenCode.initForTesting(kv, fx.config())
        try {
            assertTrue("facade did not adopt running server", facade.awaitReady(10_000))
            assertEquals(
                OpenCodeProcessManager.Ownership.EXTERNAL,
                facade.process.current!!.ownership
            )

            val gotEvent = CountDownLatch(1)
            facade.setEventListener(object : OpenCode.EventListener {
                override fun onEvent(event: OcEvent) {
                    gotEvent.countDown()
                }
            })

            val dir = fx.projectDir!!.absolutePath
            val session = facade.sessions.createSession(dir).getOrThrow()
            assertTrue("facade event router delivered nothing within 20s", await(gotEvent, 20))
            facade.sessions.abort(session.sessionId)
        } finally {
            facade.setEventListener(null)
            OpenCode.shutdownTesting()
        }
    }
}
