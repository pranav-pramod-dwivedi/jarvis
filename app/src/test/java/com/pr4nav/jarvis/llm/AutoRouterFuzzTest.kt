package com.pr4nav.jarvis.llm

import org.junit.Assert.*
import org.junit.Test

/**
 * Brutal fuzz suite for the latency-aware auto-router.
 * casual → kira-mini-1.0 · code → glm-5.3-free · mid → qwen3.8-flash-free
 */
class AutoRouterFuzzTest {

    private val MINI = KiraClient.MODEL_KIRA_MINI
    private val GLM = KiraClient.MODEL_GLM_5_3_FREE
    private val QWEN = KiraClient.MODEL_QWEN_3_8_FLASH_FREE

    private fun check(prompt: String, expected: String) {
        assertEquals("prompt=[$prompt]", expected, KiraClient.classifyAutoModel(prompt))
    }

    @Test fun casualGreetingsGoMini() {
        listOf("hi", "hii", "hiii", "hey", "hello", "yo", "namaste", "ram ram",
            "good morning", "good night", "good evening!", "Hi.", "hey...",
            "thanks", "thank you!", "shukriya", "bye", "goodbye", "see you",
            "ok", "okay", "haan", "hmm", "achha",
            "how are you", "how are you doing today", "aur batao", "kya haal",
            "who are you", "your name", "tum kaun",
            "tell me a joke", "say something funny", "haha", "lol", "i am bored", "entertain me"
        ).forEach { check(it, MINI) }
    }

    @Test fun codeTasksGoGlm() {
        listOf(
            "fix this bug in my python script",
            "debug this error: NullPointerException at Main.kt:42",
            "write a function to sort a list",
            "explain this traceback",
            "my code is not working, it crashes on start",
            "refactor this class into smaller methods",
            "write html for a landing page",
            "help with this regex ^[a-z]+$",
            "compile error: cannot find symbol",
            "build the apk, it is failing",
            "```python\nprint('hi')\n``` what does this do",
            "create a sql query for top users",
            "how do i implement caching in kotlin?",
            "write a shell script to backup /sdcard",
            "git push is failing with auth error",
            "read file /sdcard/notes.txt",
            "write file with the meeting notes",
            "edit file settings.json and restart",
            "run ls in termux",
            "execute this command for me",
            "deploy the app to my phone",
            "stacktrace says FATAL EXCEPTION, help",
            "algorithm for binary search",
            "database schema for a todo app"
        ).forEach { check(it, GLM) }
    }

    @Test fun midComplexityGoesQwen() {
        listOf(
            "what is quantum computing",
            "explain photosynthesis in simple terms",
            "why is the sky blue",
            "how does a neural network learn",
            "compare ios and android",
            "what is the difference between tcp and udp",
            "who won the election",
            "when is diwali this year",
            "where is the nearest petrol pump",
            "which phone should I buy under 20000",
            "kya JEE tough hai",
            "tum kaise kaam karte ho, explain karo",
            "summarize the key points of thermodynamics chapter",
            "give me a study plan for this week",
            "what should I eat before the exam",
            "what is the capital of australia",
            "how many planets are there",
            "why do we dream"
        ).forEach { check(it, QWEN) }
    }

    @Test fun adversarialInputsNeverCrash() {
        listOf(
            "", " ", "   \n\t  ", ".", "?", "!", ".........",
            "😂🔥👍", "12345", "a", "x".repeat(50_000),
            "```", "```\n```", "{\"action\": }", "<think>",
            "null", "NULL NULL", "undefined",
            "Hi", "HI", "hI", "  hi  ", "\nhi\n",
            "TABLE", "table", "Table.",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", // 40 a's, no signals
            "please please please please please",
            "supercalifragilisticexpialidocious antidisestablishmentarianism"
        ).forEach { input ->
            val out = KiraClient.classifyAutoModel(input)
            assertTrue(
                "prompt len=${input.length} routed to [$out]",
                out == MINI || out == GLM || out == QWEN
            )
        }
    }

    @Test fun emptyRoutesMini() {
        assertEquals(MINI, KiraClient.classifyAutoModel(""))
        assertEquals(MINI, KiraClient.classifyAutoModel("   "))
    }

    @Test fun codeBeatsCasualWhenMixed() {
        // A "hi" wrapped around a real coding task must still go deep.
        check("hi, can you debug this python error for me", GLM)
        check("hey, write a kotlin function please", GLM)
    }

    @Test fun shortOperationalUtterancesStayFast() {
        // Short device-style tasks are cheap: fastest model wins (Needle usually
        // answers these before Kira is even reached).
        listOf(
            "take a screenshot",
            "translate hello to hindi",
            "is it going to rain today",
            "set an alarm",
            "set a timer"
        ).forEach { check(it, MINI) }
    }

    @Test fun hinglishCasualStaysMini() {
        listOf("aur batao", "kya haal", "tum kaun", "shukriya", "alvida").forEach {
            assertEquals(MINI, KiraClient.classifyAutoModel(it))
        }
    }

    @Test fun substringTrapsStayOutOfGlm() {
        // Regression: "capital" is not an "api", "classic" is not a "class",
        // "description" is not a "script", "legit" is not "git".
        listOf(
            "what is the capital of australia",
            "which classic car is the best",
            "write a video description for youtube",
            "is this a legit question",
            "classroom bench allotment",
            "morning assembly speech"
        ).forEach { prompt ->
            val out = KiraClient.classifyAutoModel(prompt)
            assertTrue("trap prompt=[$prompt] routed to [$out]", out == MINI || out == QWEN)
        }
    }

    @Test fun determinism() {
        val samples = listOf("hi", "fix my code bug", "what is AI", "take me home", "write tests")
        samples.forEach { s ->
            val a = KiraClient.classifyAutoModel(s)
            repeat(20) { assertEquals("non-deterministic for [$s]", a, KiraClient.classifyAutoModel(s)) }
        }
    }

    @Test fun tierBudgets() {
        // Fast tier stays light; deep tier gets the full window.
        assertEquals(30, KiraClient.historyLimitFor(KiraClient.MODEL_KIRA_MINI))
        assertEquals(80, KiraClient.historyLimitFor(KiraClient.MODEL_QWEN_3_8_FLASH_FREE))
        assertEquals(80, KiraClient.historyLimitFor(KiraClient.MODEL_MIMO_2_5_FREE))
        assertEquals(220, KiraClient.historyLimitFor(KiraClient.MODEL_GLM_5_3_FREE))
        assertEquals(220, KiraClient.historyLimitFor(KiraClient.MODEL_KIRA_3_5_PRO))

        assertEquals(2048, KiraClient.maxTokensFor(KiraClient.MODEL_KIRA_MINI))
        assertEquals(4096, KiraClient.maxTokensFor(KiraClient.MODEL_QWEN_3_8_FLASH_FREE))
        assertEquals(16384, KiraClient.maxTokensFor(KiraClient.MODEL_GLM_5_3_FREE))

        assertTrue(KiraClient.packetBudgetFor(KiraClient.MODEL_KIRA_MINI) <
            KiraClient.packetBudgetFor(KiraClient.MODEL_GLM_5_3_FREE))
    }

    @Test fun quotaDetection() {
        assertTrue(KiraClient.isQuotaError("HTTP 402: insufficient"))
        assertTrue(KiraClient.isQuotaError("vnd_balance_exhausted"))
        assertTrue(KiraClient.isQuotaError("Please top up your wallet"))
        assertTrue(KiraClient.isQuotaError("quota exceeded"))
        assertFalse(KiraClient.isQuotaError("HTTP 500: server error"))
        assertFalse(KiraClient.isQuotaError("timed out after 100s"))
        assertFalse(KiraClient.isQuotaError(""))
        assertFalse(KiraClient.isQuotaError(null))
    }

    @Test fun resolveStartModel() {
        // Explicit pick always wins (null context = default prefs).
        assertEquals(GLM, KiraClient.resolveStartModel(null, "hi", GLM))
        // Auto classifies per task.
        assertEquals(MINI, KiraClient.resolveStartModel(null, "hi", null))
        assertEquals(MINI, KiraClient.resolveStartModel(null, "hi", KiraClient.MODEL_AUTO))
        assertEquals(GLM, KiraClient.resolveStartModel(null, "debug this crash", KiraClient.MODEL_AUTO))
        assertEquals(QWEN, KiraClient.resolveStartModel(null, "what is AI", KiraClient.MODEL_AUTO))
    }    @Test fun latencyBudget_onlyCheapOps() {
        // Classifier must be regex-only: 5k mixed prompts in well under 5s on JVM.
        val pool = listOf(
            "hi", "debug this NullPointerException now", "what is the capital of France",
            "write a python script that backs up /sdcard", "thanks", "explain black holes simply",
            "```kotlin\nval x = 1\n```", "good night", "compare dividends vs buybacks",
            "run the tests and fix failures"
        )
        val t0 = System.nanoTime()
        repeat(500) { pool.forEach { KiraClient.classifyAutoModel(it) } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("5000 classifications in ${ms}ms")
        assertTrue("classifier too slow: ${ms}ms", ms < 5000)
    }
}
