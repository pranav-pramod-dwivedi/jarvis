package com.pr4nav.jarvis

import com.pr4nav.jarvis.needle.NeedleRuntime
import com.pr4nav.jarvis.router.LanguageNormalizer
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import org.junit.Assert.*
import org.junit.Test

class BluetoothDeviceToolsTest {

    @Test
    fun testCanonicalToolRegistryContainsBluetoothTools() {
        CanonicalToolRegistry.init(android.content.ContextWrapper(null))

        val sysBt = CanonicalToolRegistry.get("system.bluetooth")
        assertNotNull("system.bluetooth must be registered", sysBt)
        assertEquals("system.bluetooth", sysBt?.name)

        val setBt = CanonicalToolRegistry.get("set_bluetooth")
        assertNotNull("set_bluetooth alias must be registered", setBt)

        val getBt = CanonicalToolRegistry.get("get_bluetooth")
        assertNotNull("get_bluetooth must be registered", getBt)
    }

    @Test
    fun testLanguageNormalizerResolvesBluetoothEnable() {
        val res = LanguageNormalizer.normalize("turn bluetooth on")
        assertNotNull(res)
        assertEquals("system.bluetooth", res?.tool)
        assertEquals(true, res?.args?.optBoolean("state"))
        assertTrue((res?.confidence ?: 0f) >= 0.90f)

        val res2 = LanguageNormalizer.normalize("enable bluetooth")
        assertNotNull(res2)
        assertEquals("system.bluetooth", res2?.tool)
        assertEquals(true, res2?.args?.optBoolean("state"))

        val resHinglish = LanguageNormalizer.normalize("bluetooth chalu karo")
        assertNotNull(resHinglish)
        assertEquals("system.bluetooth", resHinglish?.tool)
        assertEquals(true, resHinglish?.args?.optBoolean("state"))
    }

    @Test
    fun testLanguageNormalizerResolvesBluetoothDisable() {
        val res = LanguageNormalizer.normalize("turn bluetooth off")
        assertNotNull(res)
        assertEquals("system.bluetooth", res?.tool)
        assertEquals(false, res?.args?.optBoolean("state"))

        val res2 = LanguageNormalizer.normalize("disable bluetooth")
        assertNotNull(res2)
        assertEquals("system.bluetooth", res2?.tool)
        assertEquals(false, res2?.args?.optBoolean("state"))

        val resHinglish = LanguageNormalizer.normalize("bluetooth band karo")
        assertNotNull(resHinglish)
        assertEquals("system.bluetooth", resHinglish?.tool)
        assertEquals(false, resHinglish?.args?.optBoolean("state"))
    }

    @Test
    fun testLanguageNormalizerResolvesBluetoothSettingsAndStatus() {
        val resSettings = LanguageNormalizer.normalize("open bluetooth settings")
        assertNotNull(resSettings)
        assertEquals("open_settings", resSettings?.tool)
        assertEquals("bluetooth", resSettings?.args?.optString("subpage"))

        val resStatus = LanguageNormalizer.normalize("is bluetooth on?")
        assertNotNull(resStatus)
        assertEquals("get_bluetooth", resStatus?.tool)
    }

    @Test
    fun testNeedleRuntimeOfflineGrammarBluetooth() {
        val onRes = NeedleRuntime.queryOfflineGrammar("Turn Bluetooth on")
        assertEquals(1, onRes.functionCalls.size)
        assertEquals("system.bluetooth", onRes.functionCalls[0].name)
        assertEquals(true, onRes.functionCalls[0].arguments["state"])

        val offRes = NeedleRuntime.queryOfflineGrammar("Turn off Bluetooth")
        assertEquals(1, offRes.functionCalls.size)
        assertEquals("system.bluetooth", offRes.functionCalls[0].name)
        assertEquals(false, offRes.functionCalls[0].arguments["state"])
    }
}
