package com.pr4nav.jarvis.chat

import org.junit.Assert.*
import org.junit.Test

/** Refusals get retried; honest failures never do. */
class RefusalGuardTest {

    @Test fun catchesClassicCopOuts() {
        val refusals = listOf(
            "I am unable to play videos directly for you. You can go to YouTube and find a video to watch.",
            "I'm unable to open apps on your device.",
            "I cannot play music for you.",
            "I can't open YouTube.",
            "Sorry, I cannot directly control your phone.",
            "As an AI, I don't have the ability to access your device.",
            "I don't have the capability to play videos.",
            "I have no direct access to your device, so go to YouTube yourself.",
            "You can go to the Play Store yourself to do that."
        )
        refusals.forEach { assertTrue("missed refusal: [$it]", RefusalGuard.isRefusal(it)) }
    }

    @Test fun honestFailuresPass() {
        val honest = listOf(
            "File not found: /sdcard/notes.txt",
            "Command failed with exit code 1: permission denied",
            "Could not reach youtube.com: network timeout",
            "The app YouTube is not installed.",
            "Done. Opened YouTube.",
            "Playing despacito now.",
            "",
            "   ",
            "Task completed successfully."
        )
        honest.forEach { assertFalse("false positive: [$it]", RefusalGuard.isRefusal(it)) }
    }

    @Test fun nudgeDemandsTools() {
        val nudge = RefusalGuard.nudgePrompt()
        assertTrue(nudge.contains("Do not refuse"))
        assertTrue(nudge.contains("open_app"))
        assertTrue(nudge.contains("tool"))
        assertTrue(nudge.length < 500)
    }
}
