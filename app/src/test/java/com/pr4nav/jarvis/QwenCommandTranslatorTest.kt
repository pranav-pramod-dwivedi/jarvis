package com.pr4nav.jarvis

import com.pr4nav.jarvis.router.GroqCommandTranslator
import org.junit.Assert.*
import org.junit.Test

class QwenCommandTranslatorTest {

    @Test
    fun testParseBluetoothCommands() {
        val call1 = GroqCommandTranslator.parseCommandToToolCall("bluetooth(enable)", "turn on bluetooth")
        assertNotNull(call1)
        assertEquals("system.bluetooth", call1?.tool)
        assertEquals(true, call1?.args?.optBoolean("state"))

        val call2 = GroqCommandTranslator.parseCommandToToolCall("bluetooth(disable)", "turn off bluetooth")
        assertNotNull(call2)
        assertEquals("system.bluetooth", call2?.tool)
        assertEquals(false, call2?.args?.optBoolean("state"))

        val call3 = GroqCommandTranslator.parseCommandToToolCall("bluetooth(status)", "is bluetooth on?")
        assertNotNull(call3)
        assertEquals("get_bluetooth", call3?.tool)
    }

    @Test
    fun testParseTorchAndVolumeCommands() {
        val torchCall = GroqCommandTranslator.parseCommandToToolCall("torch(enable)", "turn on torch")
        assertNotNull(torchCall)
        assertEquals("system.torch", torchCall?.tool)
        assertEquals(true, torchCall?.args?.optBoolean("state"))

        val volCall = GroqCommandTranslator.parseCommandToToolCall("volume(raise)", "volume up")
        assertNotNull(volCall)
        assertEquals("system.volume", volCall?.tool)
        assertEquals("raise", volCall?.args?.optString("action"))
    }

    @Test
    fun testParseAppAndCallCommands() {
        val appCall = GroqCommandTranslator.parseCommandToToolCall("open_app(app: Chrome)", "open chrome")
        assertNotNull(appCall)
        assertEquals("open_app", appCall?.tool)
        assertEquals("Chrome", appCall?.args?.optString("app"))

        val callAction = GroqCommandTranslator.parseCommandToToolCall("call(contact: Akhil)", "call Akhil")
        assertNotNull(callAction)
        assertEquals("call_contact", callAction?.tool)
        assertEquals("Akhil", callAction?.args?.optString("number"))
    }

    @Test
    fun testParseJsonFormatFallback() {
        val jsonStr = "{\"intent\":\"system.bluetooth\",\"arguments\":{\"state\":true}}"
        val call = GroqCommandTranslator.parseCommandToToolCall(jsonStr, "turn on bluetooth")
        assertNotNull(call)
        assertEquals("system.bluetooth", call?.tool)
        assertEquals(true, call?.args?.optBoolean("state"))
    }
}
