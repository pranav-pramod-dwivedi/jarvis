package com.pr4nav.jarvis.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pr4nav.jarvis.llm.KiraClient
import com.pr4nav.jarvis.voice.KiraTtsClient
import com.pr4nav.jarvis.voice.VoiceAssistantPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Definitive wallet/voice check using the device's own stored key:
 * attempts one short cloud synthesis and reports the exact outcome.
 */
@RunWith(AndroidJUnit4::class)
class TtsWalletProbeTest {

    @Test fun probeTtsWallet() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val key = KiraClient.getApiKey(ctx)
        println("PROBE tts_key_present=${key.isNotBlank()}")
        assertTrue("no Kira API key stored on device", key.isNotBlank())

        val voice = VoiceAssistantPreferences.getCloudTtsVoice(ctx)
        println("PROBE tts_voice=$voice")
        val res = KiraTtsClient.synthesizeSpeech(ctx, "test one two", voice)
        println("PROBE tts_ok=${res != null} lastError=${KiraTtsClient.lastError}")
        // No assert on success: a 402 (empty wallet) is itself the datum.
        assertTrue("probe finished", true)
    }
}
