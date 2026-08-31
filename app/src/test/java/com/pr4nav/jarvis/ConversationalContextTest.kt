package com.pr4nav.jarvis

import android.content.ContextWrapper
import com.pr4nav.jarvis.context.ConversationalContext
import com.pr4nav.jarvis.router.CanonicalRouter
import com.pr4nav.jarvis.router.LanguageNormalizer
import com.pr4nav.jarvis.router.RouterTier
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolResult
import com.pr4nav.jarvis.tools.CanonicalToolDef
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConversationalContextTest {

    private val mockContext = ContextWrapper(null)
    private lateinit var router: CanonicalRouter

    @Before
    fun setup() {
        ConversationalContext.clear()
        CanonicalToolRegistry.init(mockContext)
        router = CanonicalRouter()
    }

    @Test
    fun testPronounResolutionCallAndMessage() {
        // Step 1: User says "Call Akhil"
        val d1 = router.route(mockContext, "call Akhil")
        assertEquals("call_contact", d1.tool)
        assertEquals("Akhil", d1.arguments?.optString("number"))
        assertEquals("Akhil", ConversationalContext.getContact())

        // Step 2: User says "Actually send him a message saying I'm outside"
        val d2 = router.route(mockContext, "send message to him saying I'm outside")
        assertEquals("send_message", d2.tool)
        assertEquals("Akhil", d2.arguments?.optString("recipient"))
        assertTrue(d2.arguments?.optString("message")?.contains("I'm outside") == true)
    }

    @Test
    fun testPronounResolutionAppLifecycle() {
        // Step 1: Open Chrome
        val d1 = router.route(mockContext, "open Chrome")
        assertEquals("open_app", d1.tool)
        assertEquals("Chrome", ConversationalContext.getApp())

        // Step 2: Close this app
        val d2 = router.route(mockContext, "close this app")
        assertEquals("close_app", d2.tool)
        assertEquals("Chrome", d2.arguments?.optString("package"))
    }

    @Test
    fun testPronounResolutionLocation() {
        // Step 1: Directions to Pune
        val d1 = router.route(mockContext, "directions to Pune")
        assertEquals("navigate", d1.tool)
        assertEquals("Pune", ConversationalContext.getLocation())

        // Step 2: Take me there
        val d2 = router.route(mockContext, "take me there")
        assertEquals("navigate", d2.tool)
        assertEquals("Pune", d2.arguments?.optString("destination"))
    }

    @Test
    fun testPronounResolutionFileOperation() {
        // Step 1: Read file /sdcard/notes.txt
        val d1 = router.route(mockContext, "read file /sdcard/notes.txt")
        assertEquals("read_file", d1.tool)
        assertEquals("/sdcard/notes.txt", ConversationalContext.getFile())

        // Step 2: Delete that file
        val d2 = router.route(mockContext, "delete file that file")
        assertEquals("delete_file", d2.tool)
        assertEquals("/sdcard/notes.txt", d2.arguments?.optString("path"))
    }

    @Test
    fun testNoHallucinationWhenContextEmpty() {
        ConversationalContext.clear()
        assertNull(ConversationalContext.getContact())
        assertNull(ConversationalContext.getApp())

        // Unresolved pronouns with no prior context must NOT hallucinate arbitrary contacts/apps
        val d = router.route(mockContext, "send message to him saying hello")
        // "him" is preserved as recipient query without inventing names
        assertEquals("send_message", d.tool)
        assertEquals("him", d.arguments?.optString("recipient"))
    }

    @Test
    fun testNegativeInformationalQueriesDoNotExecuteTools() {
        val informationalPrompts = listOf(
            "how do phone calls work?",
            "what is battery percentage?",
            "tell me about Wi-Fi",
            "what is Android Settings?",
            "explain how phone batteries work",
            "how does bluetooth work?",
            "why do batteries degrade over time?",
            "tell me about cellular networks"
        )

        for (prompt in informationalPrompts) {
            val decision = router.route(mockContext, prompt)
            assertEquals(
                "Prompt '$prompt' should not trigger deterministic tool execution",
                RouterTier.CLOUD_ESCALATION,
                decision.tier
            )
            assertNull("Tool should be null for '$prompt'", decision.tool)
        }
    }

    @Test
    fun testDistinguishActionableFromInformational() {
        // Actionable device queries -> MUST trigger Tier 1 tools
        val batteryAction = router.route(mockContext, "what's my battery")
        assertEquals(RouterTier.DETERMINISTIC_NEEDLE, batteryAction.tier)
        assertEquals("get_battery", batteryAction.tool)

        val wifiAction = router.route(mockContext, "what wifi am i on")
        assertEquals(RouterTier.DETERMINISTIC_NEEDLE, wifiAction.tier)
        assertEquals("get_wifi", wifiAction.tool)

        // Informational questions -> Escalate to conversational cloud reasoning
        val batteryInfo = router.route(mockContext, "explain how phone batteries work")
        assertEquals(RouterTier.CLOUD_ESCALATION, batteryInfo.tier)
        assertNull(batteryInfo.tool)

        val wifiInfo = router.route(mockContext, "tell me about Wi-Fi")
        assertEquals(RouterTier.CLOUD_ESCALATION, wifiInfo.tier)
        assertNull(wifiInfo.tool)
    }

    @Test
    fun testAmbiguityDetection() {
        // Register mock ambiguous contact tool response
        CanonicalToolRegistry.register(CanonicalToolDef(
            name = "test_ambiguous_call",
            description = "Mock call for testing ambiguity",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().put("name", JSONObject().put("type", "string")))
            },
            execute = { _, args ->
                val name = args.optString("name")
                if (name.equals("Alex", ignoreCase = true)) {
                    ToolResult.ambiguous(
                        "Multiple contacts found for 'Alex'. Which one should I call?",
                        listOf("Alex Work (+12345)", "Alex Home (+67890)")
                    )
                } else {
                    ToolResult.ok("Called $name")
                }
            }
        ))

        val tool = CanonicalToolRegistry.get("test_ambiguous_call")!!
        val res = tool.executeWithTimeout(mockContext, JSONObject().put("name", "Alex"))
        assertFalse(res.success)
        assertEquals(com.pr4nav.jarvis.tools.ToolStatus.AMBIGUOUS, res.status)
        assertTrue(res.error?.message?.contains("Multiple contacts found") == true)
        val options = res.data as? List<*>
        assertEquals(2, options?.size)
    }
}
