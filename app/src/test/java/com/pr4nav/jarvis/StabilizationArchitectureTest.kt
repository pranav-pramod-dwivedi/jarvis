package com.pr4nav.jarvis

import com.pr4nav.jarvis.agy.AgyManager
import com.pr4nav.jarvis.core.EnvironmentManager
import com.pr4nav.jarvis.core.ExecutionEnvironment
import com.pr4nav.jarvis.core.JarvisError
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolValidator
import com.pr4nav.jarvis.tools.ValidationResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StabilizationArchitectureTest {

    @Test
    fun testEnvironmentManagerPaths() {
        assertEquals("/data/data/com.termux/files/home", EnvironmentManager.TERMUX_HOME)
        assertEquals("/root", EnvironmentManager.UBUNTU_ROOT_IN_PROOT)

        val termuxPath = EnvironmentManager.resolveCanonicalPath(ExecutionEnvironment.TERMUX, "script.sh")
        assertEquals("/data/data/com.termux/files/home/script.sh", termuxPath)

        val ubuntuPath = EnvironmentManager.resolveCanonicalPath(ExecutionEnvironment.UBUNTU_PROOT, "test.txt")
        assertEquals("/root/test.txt", ubuntuPath)
    }

    @Test
    fun testToolValidatorRejectsUnregisteredTool() {
        val res = ToolValidator.validate(
            context = null,
            toolName = "hallucinated_invented_tool_xyz",
            rawArgs = JSONObject()
        )
        assertTrue("Unregistered tool must be rejected", res is ValidationResult.Rejected)
        val err = (res as ValidationResult.Rejected).error
        assertEquals(com.pr4nav.jarvis.core.ErrorType.UNREGISTERED_TOOL, err.errorType)
    }

    @Test
    fun testAgyManagerModelSanitization() {
        assertEquals("Gemini 3.7 Flash (Low)", AgyManager.DEFAULT_MODEL)
        assertEquals("Gemini 3.7 Flash (Low)", AgyManager.sanitizeModel("invalid-model-name-123"))
        assertEquals("Gemini 3.7 Flash (Medium)", AgyManager.sanitizeModel("Gemini 3.7 Flash (Medium)"))
        assertEquals("Claude Sonnet 4.6 (Thinking)", AgyManager.sanitizeModel("Claude Sonnet 4.6 (Thinking)"))
    }

    @Test
    fun testJarvisErrorStructure() {
        val err = JarvisError.unregisteredTool("invented_action")
        val json = err.toJsonObject()
        assertFalse(json.getBoolean("success"))
        assertEquals("UNREGISTERED_TOOL", json.getString("error_type"))
        assertTrue(json.getString("message").contains("invented_action"))
        assertNotNull(json.getString("suggested_action"))
    }
}
