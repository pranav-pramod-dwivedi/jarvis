package com.pr4nav.jarvis

import com.pr4nav.jarvis.llm.JarvisIdentity
import org.junit.Assert.*
import org.junit.Test

class JarvisIdentityTest {

    @Test
    fun testUnifiedSystemPromptEnforcesJarvisIdentity() {
        val prompt = JarvisIdentity.UNIFIED_SYSTEM_PROMPT

        assertTrue("Must assert You are JARVIS", prompt.contains("You are JARVIS"))
        assertTrue("Must prohibit Qwen identity", prompt.contains("Never identify yourself as Qwen"))
        assertTrue("Must prohibit Gemini identity", prompt.contains("Gemini"))
        assertTrue("Must instruct natural concise response", prompt.contains("Be natural, concise, and useful"))
        assertTrue("Must instruct tool execution protocol", prompt.contains("request the structured action"))
        assertTrue("Must instruct waiting for execution result", prompt.contains("Never pretend an action was completed"))
    }

    @Test
    fun testCommandTranslatorPromptSchema() {
        val prompt = JarvisIdentity.COMMAND_TRANSLATOR_PROMPT

        assertTrue("Must contain bluetooth enable schema", prompt.contains("bluetooth(enable)"))
        assertTrue("Must contain bluetooth disable schema", prompt.contains("bluetooth(disable)"))
        assertTrue("Must contain torch schema", prompt.contains("torch(enable)"))
        assertTrue("Must contain volume schema", prompt.contains("volume(raise)"))
        assertTrue("Must contain open_app schema", prompt.contains("open_app(app: <name>)"))
        assertTrue("Must contain screenshot schema", prompt.contains("screenshot()"))
    }
}
