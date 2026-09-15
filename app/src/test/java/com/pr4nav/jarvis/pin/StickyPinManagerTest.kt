package com.pr4nav.jarvis.pin

import com.pr4nav.jarvis.llm.KiraClient
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class StickyPinManagerTest {

    @Test
    fun testNocturnalSleepWindowDetection() {
        // Pranav sleeps ~6 AM to ~2 PM (14:00)
        val sleepCal1 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 6); set(Calendar.MINUTE, 30) }
        val sleepCal2 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 11); set(Calendar.MINUTE, 0) }
        val sleepCal3 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 13); set(Calendar.MINUTE, 45) }

        assertTrue("06:30 must be in sleep window", StickyPinManager.isInSleepWindow(sleepCal1))
        assertTrue("11:00 must be in sleep window", StickyPinManager.isInSleepWindow(sleepCal2))
        assertTrue("13:45 must be in sleep window", StickyPinManager.isInSleepWindow(sleepCal3))

        // Study & Waking Window (~14:00 to ~06:00 next day)
        val wakeCal1 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 15); set(Calendar.MINUTE, 30) } // Kickoff
        val wakeCal2 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 0) }  // Mid-session
        val wakeCal3 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 2); set(Calendar.MINUTE, 15) }   // Overnight study
        val wakeCal4 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 5); set(Calendar.MINUTE, 0) }   // Pre-sleep

        assertFalse("15:30 kickoff must NOT be in sleep window", StickyPinManager.isInSleepWindow(wakeCal1))
        assertFalse("23:00 mid-session must NOT be in sleep window", StickyPinManager.isInSleepWindow(wakeCal2))
        assertFalse("02:15 overnight study must NOT be in sleep window", StickyPinManager.isInSleepWindow(wakeCal3))
        assertFalse("05:00 pre-sleep review must NOT be in sleep window", StickyPinManager.isInSleepWindow(wakeCal4))
    }

    @Test
    fun testStickyPinJsonSerialization() {
        val pin = StickyPin(
            id = "test_pin_101",
            title = "study block: physics rotation",
            body = "20 problems left. keep going.",
            priority = PinPriority.JEE_STUDY,
            escalationStep = 1,
            isProactive = true
        )

        val json = pin.toJson()
        assertEquals("test_pin_101", json.getString("id"))
        assertEquals("study block: physics rotation", json.getString("title"))
        assertEquals("JEE_STUDY", json.getString("priority"))
        assertEquals(1, json.getInt("escalationStep"))
        assertTrue(json.getBoolean("isProactive"))

        val restored = StickyPin.fromJson(json)
        assertEquals(pin.id, restored.id)
        assertEquals(pin.title, restored.title)
        assertEquals(pin.body, restored.body)
        assertEquals(pin.priority, restored.priority)
        assertEquals(pin.escalationStep, restored.escalationStep)
        assertEquals(pin.isProactive, restored.isProactive)
    }

    @Test
    fun testKiraModelCascadePriorityOrder() {
        val cascade = KiraClient.FREE_MODEL_CASCADE
        assertEquals("Cascade must have 4 tiers", 4, cascade.size)
        assertEquals("Tier 1 must be glm-5.3-free", KiraClient.MODEL_GLM_5_3_FREE, cascade[0])
        assertEquals("Tier 2 must be qwen3.8-flash-free", KiraClient.MODEL_QWEN_3_8_FLASH_FREE, cascade[1])
        assertEquals("Tier 3 must be kira-mini-1.0", KiraClient.MODEL_KIRA_MINI, cascade[2])
        assertEquals("Tier 4 must be mimo-v2.5-free", KiraClient.MODEL_MIMO_2_5_FREE, cascade[3])
    }

    @Test
    fun testNagBudgetConstant() {
        assertEquals("Max proactive pins per waking period must be 6", 6, StickyPinManager.MAX_NAG_BUDGET_PER_DAY)
    }
}
