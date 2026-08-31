package com.pr4nav.jarvis

import com.pr4nav.jarvis.agy.AgyManager
import com.pr4nav.jarvis.core.*
import com.pr4nav.jarvis.router.LanguageNormalizer
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
    fun testEnvironmentPathTranslation() {
        val sharedPath = EnvironmentPath(ExecutionEnvironment.SHARED_STORAGE, "/sdcard/Projects/demo")
        val translatedToUbuntu = EnvironmentManager.translate(sharedPath, ExecutionEnvironment.UBUNTU_PROOT)
        assertNotNull(translatedToUbuntu)
        assertEquals(ExecutionEnvironment.UBUNTU_PROOT, translatedToUbuntu?.environment)
        assertEquals("/sdcard/Projects/demo", translatedToUbuntu?.path)

        val ubuntuRootPath = EnvironmentPath(ExecutionEnvironment.UBUNTU_PROOT, "/root/code")
        val translatedToTermux = EnvironmentManager.translate(ubuntuRootPath, ExecutionEnvironment.TERMUX)
        assertNotNull(translatedToTermux)
        assertEquals("/data/data/com.termux/files/home/ubuntu/root/code", translatedToTermux?.path)
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
        assertEquals(ErrorType.UNREGISTERED_TOOL, err.errorType)
    }

    @Test
    fun testToolValidatorRejectsMissingRequiredParameters() {
        val tool = com.pr4nav.jarvis.tools.CanonicalToolDef(
            name = "test_open_app",
            description = "Test tool",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("required", org.json.JSONArray().put("app"))
            },
            execute = { _, _ -> com.pr4nav.jarvis.tools.ToolResult.ok() }
        )
        CanonicalToolRegistry.register(tool)
        val res = ToolValidator.validate(
            context = null,
            toolName = "test_open_app",
            rawArgs = JSONObject() // Missing required 'app' parameter
        )
        assertTrue("Missing required parameters must be rejected", res is ValidationResult.Rejected)
    }

    @Test
    fun testExecutionJournalRedactionAndBounds() {
        ExecutionJournal.clear()
        val entry = JournalEntry(
            executionId = ExecutionJournal.generateExecutionId(),
            rawRequest = "call contact with key AIzaSyABC1234567890123456789012345678",
            normalizedIntent = "call_contact",
            tool = "comms.call",
            args = JSONObject().put("number", "1234567890"),
            backend = "ANDROID_NATIVE",
            phase = ExecutionPhase.COMPLETED,
            success = true,
            verified = true,
            durationMs = 45L
        )

        ExecutionJournal.record(entry)
        val recent = ExecutionJournal.getRecent(10)
        assertEquals(1, recent.size)

        val json = recent.first().toJsonObject()
        assertTrue(json.getString("request").contains("[REDACTED_API_KEY]"))
        assertFalse(json.getString("request").contains("AIzaSy"))
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

    @Test
    fun testFlashlightAndDeviceIntentNormalization() {
        // English
        val torchOnEn = LanguageNormalizer.normalize("turn on flashlight")
        assertNotNull(torchOnEn)
        assertEquals("system.torch", torchOnEn?.tool)
        assertTrue(torchOnEn?.args?.getBoolean("state") == true)

        val torchOffEn = LanguageNormalizer.normalize("turn off flashlight")
        assertNotNull(torchOffEn)
        assertEquals("system.torch", torchOffEn?.tool)
        assertTrue(torchOffEn?.args?.getBoolean("state") == false)

        // Hinglish / Hindi
        val torchOnHi = LanguageNormalizer.normalize("torch chalu kar")
        assertNotNull(torchOnHi)
        assertEquals("system.torch", torchOnHi?.tool)
        assertTrue(torchOnHi?.args?.getBoolean("state") == true)

        val torchOnHi2 = LanguageNormalizer.normalize("light on kar")
        assertNotNull(torchOnHi2)
        assertEquals("system.torch", torchOnHi2?.tool)

        val torchOnHi3 = LanguageNormalizer.normalize("bhai torch chala de")
        assertNotNull(torchOnHi3)
        assertEquals("system.torch", torchOnHi3?.tool)

        val torchOffHi = LanguageNormalizer.normalize("torch bandh kar yaar")
        assertNotNull(torchOffHi)
        assertEquals("system.torch", torchOffHi?.tool)
        assertTrue(torchOffHi?.args?.getBoolean("state") == false)

        // Volume
        val volUp = LanguageNormalizer.normalize("volume badha")
        assertNotNull(volUp)
        assertEquals("system.volume", volUp?.tool)
        assertEquals("raise", volUp?.args?.getString("action"))

        val volMute = LanguageNormalizer.normalize("phone thoda silent kar")
        assertNotNull(volMute)
        assertEquals("system.volume", volMute?.tool)
        assertEquals("mute", volMute?.args?.getString("action"))
    }
}
