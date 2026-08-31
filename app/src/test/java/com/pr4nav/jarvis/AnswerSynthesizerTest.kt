package com.pr4nav.jarvis

import com.pr4nav.jarvis.response.AnswerSynthesizer
import com.pr4nav.jarvis.response.ResponseMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnswerSynthesizerTest {

    @Test
    fun testResponseModeClassification() {
        // 1. Informational queries -> SEARCH_THEN_ANSWER
        val m1 = AnswerSynthesizer.determineResponseMode("Who is Modi?", "INFORMATION")
        assertEquals(ResponseMode.SEARCH_THEN_ANSWER, m1)

        val m2 = AnswerSynthesizer.determineResponseMode("Who is Narendra Modi?", "INFORMATION")
        assertEquals(ResponseMode.SEARCH_THEN_ANSWER, m2)

        val m3 = AnswerSynthesizer.determineResponseMode("What happened today?", "INFORMATION")
        assertEquals(ResponseMode.SEARCH_THEN_ANSWER, m3)

        // 2. Direct calculations -> ANSWER
        val m4 = AnswerSynthesizer.determineResponseMode("What is 2 + 2?", "CONVERSATION")
        assertEquals(ResponseMode.ANSWER, m4)

        // 3. Explicit search requests -> SEARCH_ONLY
        val m5 = AnswerSynthesizer.determineResponseMode("Search the web for Narendra Modi", "ACTION")
        assertEquals(ResponseMode.SEARCH_ONLY, m5)

        val m6 = AnswerSynthesizer.determineResponseMode("Search for latest AI news", "ACTION")
        assertEquals(ResponseMode.SEARCH_ONLY, m6)
    }

    @Test
    fun testFactualInformationSynthesis() {
        // Who is Modi
        val ansModi = AnswerSynthesizer.synthesize("Who is Modi?", "search_web", JSONObject())
        assertTrue("Answer must mention Narendra Modi and Prime Minister of India", ansModi.contains("Prime Minister of India"))
        assertFalse("Answer must NOT contain internal JSON or tool names", ansModi.contains("search_web") || ansModi.contains("100/100"))

        // Capital of France
        val ansParis = AnswerSynthesizer.synthesize("What is the capital of France?", "search_web", JSONObject())
        assertTrue(ansParis.contains("Paris"))

        // Math
        val ansMath = AnswerSynthesizer.synthesize("What is 2 + 2?", "calculator", JSONObject(), ResponseMode.ANSWER)
        assertEquals("2 + 2 = 4", ansMath)
    }

    @Test
    fun testExplicitSearchOnlySynthesis() {
        val ans = AnswerSynthesizer.synthesize(
            "Search the web for Narendra Modi",
            "search_web",
            JSONObject(),
            ResponseMode.SEARCH_ONLY
        )
        assertTrue(ans.startsWith("Here are the web search results for"))
    }

    @Test
    fun testTelemetryAndDeviceSynthesis() {
        // Battery
        val batData = JSONObject().put("level", 85).put("charging", true)
        val batAns = AnswerSynthesizer.synthesize("battery status", "get_battery", batData)
        assertEquals("Your battery is at 85% and is currently charging.", batAns)

        // WiFi
        val wifiData = JSONObject().put("connected", true).put("ssid", "Studio-5G")
        val wifiAns = AnswerSynthesizer.synthesize("wifi status", "get_wifi", wifiData)
        assertEquals("You are currently connected to Wi-Fi network \"Studio-5G\".", wifiAns)

        // Bluetooth
        val btData = JSONObject().put("enabled", true)
        val btAns = AnswerSynthesizer.synthesize("bluetooth status", "get_bluetooth", btData)
        assertEquals("Bluetooth is currently turned on.", btAns)

        // Torch
        val torchData = JSONObject().put("state", true)
        val torchAns = AnswerSynthesizer.synthesize("turn on flashlight", "system.torch", torchData)
        assertEquals("Flashlight turned on.", torchAns)
    }
}
