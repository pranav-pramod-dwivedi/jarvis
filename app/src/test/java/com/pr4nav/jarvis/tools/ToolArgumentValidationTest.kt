package com.pr4nav.jarvis.tools

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolArgumentValidationTest {

    @Test
    fun testRejectsInvalidToolActions() {
        CanonicalToolRegistry.init(android.content.ContextWrapper(null))

        // Valid action passes
        val validRes = ToolValidator.validate(
            null,
            "system.bluetooth",
            JSONObject().put("action", "enable")
        )
        assertTrue("Valid action must pass", validRes is ValidationResult.Valid)

        // Invalid action banana rejected
        val invalidRes = ToolValidator.validate(
            null,
            "system.bluetooth",
            JSONObject().put("action", "banana")
        )
        assertTrue("Invalid action banana must be rejected", invalidRes is ValidationResult.Rejected)
        val rejected = invalidRes as ValidationResult.Rejected
        assertEquals("INVALID_TOOL_ACTION", rejected.reasonCode)
    }

    @Test
    fun testRejectsWorkspaceBoundaryViolationInFileTools() {
        CanonicalToolRegistry.init(android.content.ContextWrapper(null))

        val invalidPathRes = ToolValidator.validate(
            null,
            "write_file",
            JSONObject().put("path", "/root/calculator/app.py").put("content", "print(1)")
        )
        assertTrue("Writing to /root must be rejected as WORKSPACE_BOUNDARY", invalidPathRes is ValidationResult.Rejected)
        val rej = invalidPathRes as ValidationResult.Rejected
        assertEquals("WORKSPACE_BOUNDARY", rej.reasonCode)
    }
}
