package com.pr4nav.jarvis

import com.pr4nav.jarvis.llm.GgufMetadataParser
import com.pr4nav.jarvis.llm.GgufParsedMetadata
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GgufMetadataParserTest {

    @Test
    fun testIdentityVerificationPassAndFail() {
        val metadata = GgufParsedMetadata(
            isValidGguf = true,
            version = 3,
            tensorCount = 339,
            kvCount = 26,
            architecture = "qwen2",
            modelName = "qwen2.5-1.5b-instruct",
            sizeLabel = "1.8B",
            parameterCountEstimate = "1.8B",
            fileType = 15,
            quantization = "Q4_K_M",
            contextLength = 32768
        )

        // Matching model ID -> PASS
        val checkPass = metadata.verifyIdentity("qwen2.5-1.5b-instruct-q4")
        assertTrue("Matching model should pass identity check", checkPass.isIdentityPass)
        assertEquals("PASS", checkPass.statusText)

        // Mismatched model ID (Qwen3.5 vs Qwen2.5) -> FAIL
        val checkFail = metadata.verifyIdentity("qwen3.5-2b-instruct-q4")
        assertFalse("Mismatched model must fail identity check", checkFail.isIdentityPass)
        assertTrue(checkFail.statusText.contains("FAIL"))
    }
}
