package com.pr4nav.jarvis

import android.content.Context
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class CanonicalToolRegistryTest {

    private val mockContext = android.content.ContextWrapper(null)

    @Before
    fun setup() {

        // Register test canonical tools
        CanonicalToolRegistry.register(CanonicalToolDef(
            name = "test_echo",
            description = "Echoes input text",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("msg", JSONObject().put("type", "string"))
                })
                put("required", JSONArray().put("msg"))
            },
            defaultTimeoutMs = 1000L,
            execute = { _, args ->
                ToolResult.ok("Echo: " + args.optString("msg"))
            }
        ))

        CanonicalToolRegistry.register(CanonicalToolDef(
            name = "test_timeout",
            description = "Sleeps past timeout",
            argumentSchema = JSONObject().apply {
                put("type", "object")
            },
            defaultTimeoutMs = 50L,
            execute = { _, _ ->
                Thread.sleep(300L)
                ToolResult.ok("Done")
            }
        ))
    }

    @Test
    fun testToolLookupAndNames() {
        val tool = CanonicalToolRegistry.get("test_echo")
        assertNotNull(tool)
        assertEquals("test_echo", tool!!.name)
        assertTrue(CanonicalToolRegistry.names().contains("test_echo"))
    }

    @Test
    fun testArgumentValidationMissingRequired() {
        val tool = CanonicalToolRegistry.get("test_echo")!!
        // Missing "msg" param
        val res = tool.executeWithTimeout(mockContext, JSONObject())
        assertFalse(res.success)
        assertEquals("INVALID_ARGUMENTS", res.error?.code)
        assertTrue(res.error?.message?.contains("Missing required argument: msg") == true)
    }

    @Test
    fun testSuccessfulExecution() {
        val tool = CanonicalToolRegistry.get("test_echo")!!
        val args = JSONObject().put("msg", "hello canonical world")
        val res = tool.executeWithTimeout(mockContext, args)
        assertTrue(res.success)
        assertEquals("Echo: hello canonical world", res.data)
        assertNull(res.error)
    }

    @Test
    fun testTimeoutEnforcement() {
        val tool = CanonicalToolRegistry.get("test_timeout")!!
        val res = tool.executeWithTimeout(mockContext, JSONObject(), timeoutOverrideMs = 50L)
        assertFalse(res.success)
        assertEquals("TIMEOUT", res.error?.code)
        assertTrue(res.error?.message?.contains("timed out") == true)
    }

    @Test
    fun testStructuredResultJson() {
        val okRes = ToolResult.ok("payload_123", 42L)
        val json = okRes.toJsonObject()
        assertTrue(json.getBoolean("success"))
        assertEquals("payload_123", json.getString("data"))
        assertEquals(42L, json.getLong("latencyMs"))

        val failRes = ToolResult.failure("CUSTOM_ERR", "something broke")
        val failJson = failRes.toJsonObject()
        assertFalse(failJson.getBoolean("success"))
        val errObj = failJson.getJSONObject("error")
        assertEquals("CUSTOM_ERR", errObj.getString("code"))
        assertEquals("something broke", errObj.getString("message"))
    }
}
