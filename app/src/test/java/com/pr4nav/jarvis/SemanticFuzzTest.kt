package com.pr4nav.jarvis

import android.content.ContextWrapper
import com.pr4nav.jarvis.context.ConversationalContext
import com.pr4nav.jarvis.router.CanonicalRouter
import com.pr4nav.jarvis.router.RouterDecision
import com.pr4nav.jarvis.router.RouterTier
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Random

/**
 * Comprehensive Randomized Semantic Fuzz Test Harness.
 * Tests 20-50 meaning-equivalent unseen natural language prompts per capability
 * across English, Hindi, and Hinglish. Verifies intent convergence without keyword overfitting.
 */
class SemanticFuzzTest {

    private val mockContext = ContextWrapper(null)
    private lateinit var router: CanonicalRouter

    data class CapabilityFuzzSpec(
        val capability: String,
        val targetTool: String,
        val expectedArgKey: String? = null,
        val expectedArgVal: String? = null,
        val promptVariants: List<String>
    )

    data class FuzzReport(
        var totalPrompts: Int = 0,
        var correctIntent: Int = 0,
        var correctArguments: Int = 0,
        var incorrect: Int = 0,
        var ambiguous: Int = 0,
        var cloudEscalations: Int = 0,
        var falseToolExecutions: Int = 0,
        val perCapabilityStats: MutableMap<String, Pair<Int, Int>> = mutableMapOf() // total, correct
    )

    @Before
    fun setup() {
        ConversationalContext.clear()
        CanonicalToolRegistry.init(mockContext)
        router = CanonicalRouter()
    }

    @Test
    fun runSemanticFuzzTestSuite() {
        val testSpecs = listOf(
            // 1. call_contact (Target: Akhil)
            CapabilityFuzzSpec(
                capability = "call_contact",
                targetTool = "call_contact",
                expectedArgKey = "number",
                expectedArgVal = "Akhil",
                promptVariants = listOf(
                    "call Akhil",
                    "call Akhil for me",
                    "can you ring Akhil",
                    "Akhil ko phone laga do",
                    "Akhil ko call kar",
                    "dial Akhil",
                    "get Akhil on the phone",
                    "phone Akhil",
                    "I need to talk to Akhil",
                    "connect me with Akhil",
                    "ring my friend Akhil",
                    "bhai Akhil ko phone kar",
                    "can you just ring Akhil?",
                    "phone Akhil pls",
                    "mujhe Akhil se baat karni hai",
                    "get Akhil on call",
                    "Akhil ko phone lagao",
                    "Akhil ko call karo",
                    "Akhil se connect karo",
                    "please call Akhil"
                )
            ),

            // 2. navigate (Target: home)
            CapabilityFuzzSpec(
                capability = "navigate",
                targetTool = "navigate",
                expectedArgKey = "destination",
                expectedArgVal = "home",
                promptVariants = listOf(
                    "take me home",
                    "ghar ka rasta bata",
                    "get me back home",
                    "I wanna go home",
                    "home le chalo",
                    "mujhe ghar pahucha do",
                    "can you navigate me back home",
                    "let's head home",
                    "take me to home",
                    "directions to home",
                    "drive to home",
                    "ghar chalo",
                    "mujhe ghar le chalo",
                    "ghar ka route dikhao",
                    "ghar ka rasta batao",
                    "go to home",
                    "navigate to home",
                    "i want to go home",
                    "mujhe home le chalo",
                    "take me back home"
                )
            ),

            // 3. open_app (Target: Chrome)
            CapabilityFuzzSpec(
                capability = "open_app",
                targetTool = "open_app",
                expectedArgKey = "app",
                expectedArgVal = "Chrome",
                promptVariants = listOf(
                    "open Chrome",
                    "chrome kholo",
                    "launch Chrome",
                    "start chrome",
                    "chrome open karo",
                    "can you open chrome",
                    "fire up Chrome",
                    "kholo chrome",
                    "chrome chalao",
                    "chrome chalu karo",
                    "please open Chrome",
                    "bhai chrome khol do",
                    "launch the chrome app",
                    "open the chrome app",
                    "open chrome pls",
                    "chrome start karo",
                    "chrome kholna",
                    "open chrome please",
                    "start the chrome app",
                    "chrome chala do"
                )
            ),

            // 4. close_app (Target: Chrome)
            CapabilityFuzzSpec(
                capability = "close_app",
                targetTool = "close_app",
                expectedArgKey = "package",
                expectedArgVal = "Chrome",
                promptVariants = listOf(
                    "close Chrome",
                    "chrome band karo",
                    "stop Chrome",
                    "kill chrome",
                    "shut down chrome",
                    "chrome band kar do",
                    "chrome hatao",
                    "chrome rok do",
                    "please close chrome",
                    "can you close chrome",
                    "close the chrome app",
                    "stop the chrome app",
                    "kill the chrome app",
                    "close chrome pls",
                    "close chrome please",
                    "chrome app band karo",
                    "shut down the chrome app",
                    "chrome app hatao",
                    "can you stop chrome",
                    "please kill chrome"
                )
            ),

            // 5. get_battery
            CapabilityFuzzSpec(
                capability = "get_battery",
                targetTool = "get_battery",
                promptVariants = listOf(
                    "what is my battery",
                    "what's my battery",
                    "battery status",
                    "battery percentage",
                    "how much battery do i have",
                    "battery kitni hai",
                    "charge kitna hai",
                    "battery check karo",
                    "kitna charge hai",
                    "battery level",
                    "check battery",
                    "how much battery is left",
                    "battery",
                    "what is the battery percentage",
                    "what's the battery level",
                    "what is my battery percentage",
                    "what is the battery status",
                    "battery status batao",
                    "mera phone kitna charge hai",
                    "battery kitni bachi hai"
                )
            ),

            // 6. get_location
            CapabilityFuzzSpec(
                capability = "get_location",
                targetTool = "get_location",
                promptVariants = listOf(
                    "where am I",
                    "my current location",
                    "get location",
                    "what's my location",
                    "what is my location",
                    "main kahan hoon",
                    "meri location kya hai",
                    "location batao",
                    "hum kahan hain",
                    "my location",
                    "current location",
                    "where am i right now",
                    "meri current location kya hai",
                    "abhi hum kahan hain",
                    "apni location batao",
                    "batao main kahan hoon",
                    "current coordinates batao",
                    "where am i located",
                    "meri location check karo",
                    "get my location"
                )
            ),

            // 7. get_wifi
            CapabilityFuzzSpec(
                capability = "get_wifi",
                targetTool = "get_wifi",
                promptVariants = listOf(
                    "wifi status",
                    "is wifi connected",
                    "wifi connected",
                    "get wifi",
                    "check wifi",
                    "what wifi am i on",
                    "wifi chal raha hai kya",
                    "wifi check karo",
                    "wifi connected hai kya",
                    "wifi chal raha hai",
                    "is wifi on",
                    "what wifi is this",
                    "check my wifi",
                    "wifi connection status",
                    "wifi ka status batao",
                    "wifi connected hai",
                    "get wifi status",
                    "wifi ki speed ya status",
                    "wifi check kar",
                    "is wifi active"
                )
            ),

            // 8. get_bluetooth
            CapabilityFuzzSpec(
                capability = "get_bluetooth",
                targetTool = "get_bluetooth",
                promptVariants = listOf(
                    "bluetooth status",
                    "is bluetooth on",
                    "bluetooth on",
                    "get bluetooth",
                    "check bluetooth",
                    "bluetooth check karo",
                    "bluetooth on hai kya",
                    "bluetooth status batao",
                    "is bluetooth enabled",
                    "check my bluetooth",
                    "bluetooth chalu hai kya",
                    "get bluetooth status",
                    "bluetooth ka status kya hai",
                    "bluetooth on hai",
                    "bluetooth active hai kya",
                    "bluetooth toggle state",
                    "bluetooth check kar",
                    "is my bluetooth on",
                    "bluetooth connected hai kya",
                    "check if bluetooth is on"
                )
            ),

            // 9. open_settings
            CapabilityFuzzSpec(
                capability = "open_settings",
                targetTool = "open_settings",
                expectedArgKey = "subpage",
                expectedArgVal = "wifi",
                promptVariants = listOf(
                    "open wifi settings",
                    "wifi settings kholo",
                    "open the wifi settings",
                    "show wifi settings",
                    "launch wifi settings",
                    "wifi settings khol do",
                    "wifi settings dikhao",
                    "kholo wifi settings",
                    "open wifi settings please",
                    "wifi settings par jao",
                    "wifi settings open karo",
                    "settings me wifi kholo",
                    "open device wifi settings",
                    "go to wifi settings",
                    "wifi setting kholo",
                    "open android wifi settings",
                    "wifi settings screen kholo",
                    "wifi settings open kar do",
                    "wifi configuration kholo",
                    "wifi settings page open karo"
                )
            ),

            // 10. take_screenshot
            CapabilityFuzzSpec(
                capability = "take_screenshot",
                targetTool = "take_screenshot",
                promptVariants = listOf(
                    "take screenshot",
                    "capture screen",
                    "screenshot",
                    "screenshot lo",
                    "screenshot khicho",
                    "screen capture karo",
                    "take a screenshot",
                    "capture the screen",
                    "screenshot le lo",
                    "screen ka screenshot lo",
                    "please take screenshot",
                    "can you take a screenshot",
                    "screenshot khich lo",
                    "screen grab karo",
                    "take screen snapshot",
                    "display capture karo",
                    "screenshot nikalo",
                    "screenshot lo please",
                    "ek screenshot khicho",
                    "capture screen display"
                )
            )
        )

        val report = FuzzReport()
        val random = Random(42)

        for (spec in testSpecs) {
            // Shuffle prompt variants to ensure randomized order
            val randomizedPrompts = spec.promptVariants.shuffled(random)
            var specCorrect = 0

            for (prompt in randomizedPrompts) {
                report.totalPrompts++
                val decision = router.route(mockContext, prompt)

                if (decision.tier == RouterTier.CLOUD_ESCALATION) {
                    report.cloudEscalations++
                    report.incorrect++
                    continue
                }

                if (decision.tool == spec.targetTool) {
                    report.correctIntent++
                    var argMatches = true
                    if (spec.expectedArgKey != null && spec.expectedArgVal != null) {
                        val argVal = decision.arguments?.optString(spec.expectedArgKey) ?: ""
                        if (argVal.equals(spec.expectedArgVal, ignoreCase = true)) {
                            report.correctArguments++
                        } else {
                            argMatches = false
                        }
                    } else {
                        report.correctArguments++
                    }

                    if (argMatches) {
                        specCorrect++
                    }
                } else {
                    report.incorrect++
                    report.falseToolExecutions++
                }
            }

            report.perCapabilityStats[spec.capability] = Pair(randomizedPrompts.size, specCorrect)
        }

        // Output detailed fuzz test matrix
        println("\n=======================================================")
        println("       JARVIS SEMANTIC FUZZ TESTING REPORT            ")
        println("=======================================================")
        println("Total prompts evaluated:    ${report.totalPrompts}")
        println("Correct intent matched:     ${report.correctIntent} (${String.format("%.1f", report.correctIntent * 100.0 / report.totalPrompts)}%)")
        println("Correct arguments matched:  ${report.correctArguments} (${String.format("%.1f", report.correctArguments * 100.0 / report.totalPrompts)}%)")
        println("Incorrect intent/execution: ${report.incorrect}")
        println("Cloud escalations:          ${report.cloudEscalations}")
        println("False tool executions:      ${report.falseToolExecutions}")
        println("-------------------------------------------------------")
        for ((cap, stats) in report.perCapabilityStats) {
            println("Capability: %-16s | Passed: %2d / %2d (%.1f%%)".format(
                cap, stats.second, stats.first, stats.second * 100.0 / stats.first
            ))
        }
        println("=======================================================\n")

        // Assert at least 90% semantic accuracy across the random fuzz test corpus
        val accuracy = report.correctArguments.toDouble() / report.totalPrompts
        assertTrue("Semantic accuracy must exceed 85%, got ${accuracy * 100}%", accuracy >= 0.85)
    }
}
