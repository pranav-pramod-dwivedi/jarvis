package com.pr4nav.jarvis.llm

import org.junit.Assert.*
import org.junit.Test

class AgentContextGroundingTest {

    @Test
    fun testUnifiedPromptContainsStrictGroundingDirectives() {
        val prompt = JarvisIdentity.UNIFIED_SYSTEM_PROMPT

        assertTrue("Must assert running inside JARVIS", prompt.contains("You are running inside JARVIS"))
        assertTrue("Must check before claiming exists", prompt.contains("Before claiming that something exists, check it"))
        assertTrue("Must inspect filesystem before guessing path", prompt.contains("Before guessing a file path, inspect the filesystem"))
        assertTrue("Must anchor workspace to /storage/emulated/0/JARVIS/workspace", prompt.contains("/storage/emulated/0/JARVIS/workspace"))
        assertTrue("Must forbid inventing tool results", prompt.contains("Never invent tool results"))
        assertTrue("Must mandate verification", prompt.contains("Never claim success without verification"))
    }
}
