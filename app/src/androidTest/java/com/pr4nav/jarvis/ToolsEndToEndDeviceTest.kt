package com.pr4nav.jarvis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pr4nav.jarvis.capabilities.Capabilities
import com.pr4nav.jarvis.tools.JarvisToolRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolsEndToEndDeviceTest {

    private fun exec(name: String, args: String = "{}") =
        JarvisToolRegistry.execute(name, args)

    @Before
    fun setup() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Capabilities.init(ctx)
        Fs.init(ctx)
        JarvisToolRegistry.registerAll(ctx)
    }

    @Test
    fun fileWriteReadListDeleteRoundTrip() {
        val dir = ctx().getExternalFilesDir(null)!!.absolutePath + "/e2e"
        exec("file.mkdir", """{"path":"$dir"}""")
        val path = "$dir/test-${System.currentTimeMillis()}.txt"

        val w = exec("file.create", """{"path":"$path"}""")
        assertTrue("create failed: $w", w.optBoolean("ok"))

        val wr = exec("file.write", """{"path":"$path","content":"hello jarvis"}""")
        assertTrue("write failed: $wr", wr.optBoolean("ok"))

        val r = exec("file.read", """{"path":"$path"}""")
        assertTrue("read failed: $r", r.optBoolean("ok"))
        assertEquals("hello jarvis", r.getJSONObject("data").getString("content"))

        val l = exec("file.list", """{"path":"$dir"}""")
        assertTrue(l.optBoolean("ok"))

        val st = exec("file.stat", """{"path":"$path"}""")
        assertTrue(st.optBoolean("ok"))
        assertEquals("text/plain", st.getJSONObject("data").getString("mime"))

        val d = exec("file.delete", """{"path":"$path"}""")
        assertTrue(d.optBoolean("ok"))
    }

    @Test
    fun fileRenameCopySearch() {
        val dir = ctx().getExternalFilesDir(null)!!.absolutePath + "/e2e2"
        val a = "$dir/orig.txt"
        exec("file.mkdir", """{"path":"$dir"}""")
        exec("file.write", """{"path":"$a","content":"searchable-content-xyz"}""")

        val b = "$dir/moved.txt"
        val mv = exec("file.move", """{"src":"$a","dst":"$b"}""")
        assertTrue("move failed: $mv", mv.optBoolean("ok"))

        val statGone = exec("file.stat", """{"path":"$a"}""")
        assertFalse(
            "source should be gone after move: $statGone",
            statGone.optBoolean("ok")
        )

        val cp = exec("file.copy", """{"src":"$b","dst":"$dir/copy.txt"}""")
        assertTrue(cp.optBoolean("ok"))

        val s = exec("file.search", """{"root":"$dir","query":"moved","max":10}""")
        assertTrue(s.optBoolean("ok"))

        exec("file.delete", """{"path":"$dir"}""")
    }

    @Test
    fun clipboardWriteThenRead() {
        val payload = "jarvis-clip-${System.currentTimeMillis()}"
        lateinit var w: JSONObject
        lateinit var r: JSONObject

        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            .onActivity { act ->
                w = exec("clipboard.write", """{"text":"$payload"}""")
                r = exec("clipboard.read")
            }
        assertTrue("clipboard write failed: $w", w.optBoolean("ok"))

        if (!r.optBoolean("ok")) {
            val err = r.optString("error")
            assertTrue(
                "read failure must be the documented focus restriction, got: $err",
                err.contains("restricted") || err.contains("empty")
            )
            return
        }
        assertEquals(payload, r.getJSONObject("data").getString("text"))
    }

    @Test
    fun audioVolumeGetAndSet() {
        val g = exec("audio.volume", """{"stream":"music"}""")
        assertTrue(g.optBoolean("ok"))
        val max = g.getJSONObject("data").getInt("max")

        val s = exec("audio.setvolume", """{"stream":"music","value":${max / 2}}""")
        assertTrue(s.optBoolean("ok"))
        assertEquals(max / 2, s.getJSONObject("data").getInt("volume"))
    }

    @Test
    fun appDiscoveryFindsLauncherAndSettings() {
        val list = exec("app.search", "{}")
        assertTrue(list.optBoolean("ok"))
        assertTrue(list.getJSONArray("data").length() > 0)

        val installed = exec("app.installed", """{"name":"com.android.settings"}""")
        assertTrue(installed.optBoolean("ok"))
    }

    @Test
    fun deviceInfoAndBatteryReturnData() {
        val i = exec("device.info")
        assertTrue(i.optBoolean("ok"))
        val b = exec("device.battery")
        assertTrue(b.optBoolean("ok"))
    }

    @Test
    fun rootStatusNeverCrashesAndIsStructured() {
        val s = exec("root.status")
        assertTrue(s.optBoolean("ok"))
        val state = s.getJSONObject("data").getString("state")
        assertTrue(state in setOf("CHECKING", "AVAILABLE", "UNAVAILABLE"))
    }

    @Test
    fun termuxStatusStructuredEvenWhenAbsent() {
        val s = exec("termux.status")
        assertTrue(
            "termux.status must succeed with booleans even if Termux missing",
            s.optBoolean("ok")
        )
    }

    @Test
    fun deniedStatesReturnUsefulErrorsNotCrashes() {
        val loc = exec("location.current") // permission not granted in test env unless auto-granted
        if (!loc.optBoolean("ok")) assertTrue(loc.getString("error").isNotBlank())

        val notif = exec("notification.list") // listener typically not granted
        if (!notif.optBoolean("ok")) assertTrue(notif.getString("error").isNotBlank())

        val acc = exec("accessibility.inspect") // service likely not enabled
        if (!acc.optBoolean("ok")) assertTrue(acc.getString("error").isNotBlank())
    }

    private fun ctx() = InstrumentationRegistry.getInstrumentation().targetContext
}
