package com.pr4nav.jarvis

import com.pr4nav.jarvis.capabilities.CapabilityResult
import com.pr4nav.jarvis.tools.JarvisToolRegistry
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolRegistryTest {

    @Before
    fun reset() {
        JarvisToolRegistry.register(
            listOf(
                ToolDef("echo", "returns input", """{"text":"..."}""", null,
                    { a -> com.pr4nav.jarvis.capabilities.CapabilityResult
                        .ok(JSONObject().put("text", a.getString("text")).toString()).envelope() }),
                ToolDef("gated", "denied tool", "{}",
                    { "capability not permitted" },
                    { _ -> JSONObject().put("never", true) }),
                ToolDef("boom", "throws", "{}", null,
                    { _ -> error("kaput") })
            )
        )
    }

    @Test
    fun `unknown tool returns structured error`() {
        val r = JarvisToolRegistry.execute("nope", null)
        assertFalse(r.getBoolean("ok"))
        assertTrue(r.getString("error").contains("unknown tool"))
    }

    @Test
    fun `successful tool wraps data in ok envelope`() {
        val r = JarvisToolRegistry.execute("echo", """{"text":"hi"}""")
        assertTrue(r.getBoolean("ok"))
        assertEquals("hi", r.getJSONObject("data").getString("text"))
    }

    @Test
    fun `blank args become empty json object`() {
        val r = JarvisToolRegistry.execute("boom", "")
        assertFalse(r.getBoolean("ok"))
        assertTrue(r.getString("error").contains("kaput"))
    }

    @Test
    fun `malformed args return structured error not crash`() {
        val r = JarvisToolRegistry.execute("echo", "{not json")
        assertFalse(r.getBoolean("ok"))
    }

    @Test
    fun `gate denial short circuits with reason`() {
        val r = JarvisToolRegistry.execute("gated", null)
        assertFalse(r.getBoolean("ok"))
        assertEquals("capability not permitted", r.getString("error"))
    }

    @Test
    fun `handler exception becomes error envelope`() {
        val r = JarvisToolRegistry.execute("boom", null)
        assertFalse(r.getBoolean("ok"))
        assertTrue(r.getString("error").isNotBlank())
    }
}

class CapabilityResultTest {

    @Test
    fun `envelope shape for success`() {
        val e = CapabilityResult.ok("""{"a":1}""", "extra" to "x").envelope()
        assertTrue(e.getBoolean("ok"))
        assertEquals(1, e.getJSONObject("data").getInt("a"))
        assertEquals("x", e.getString("extra"))
        assertFalse(e.has("error"))
    }

    @Test
    fun `envelope shape for failure`() {
        val e = CapabilityResult.fail("Accessibility access is required").envelope()
        assertFalse(e.getBoolean("ok"))
        assertEquals("Accessibility access is required", e.getString("error"))
        assertFalse(e.has("data"))
    }
}
