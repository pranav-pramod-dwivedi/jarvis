package com.pr4nav.jarvis.llm

import com.pr4nav.jarvis.chat.AgentStreamEvent
import com.pr4nav.jarvis.router.UnifiedAssistantDispatcher
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveTurnResultTest {
    private fun finish(
        completed: Boolean = true,
        final: String = "Hello, how are you?",
        streamed: String = "Hello, how are you?",
        error: String? = null
    ) = GeminiLiveClient.finishOneShotTurn(completed, final, streamed, "", true, error)

    @Test fun completedAnswerReplacesItsStreamingFragments() {
        val result = finish()
        assertTrue(result.success)
        assertEquals("Hello, how are you?", result.text)
        assertTrue(result.audioPlayed)
    }

    @Test fun authoritativeFinalCanDifferFromSpokenTranscription() {
        assertEquals("The result is 3.14.", finish(final = "The result is 3.14.", streamed = "Three point one four.").text)
    }

    @Test fun emptyFinalFallsBackToStreamOnlyOnCompletedTurn() {
        assertEquals("Hello, how are you?", finish(final = "").text)
        assertTrue(finish(final = "").success)
        assertFalse(finish(completed = false, final = "").success)
    }

    @Test fun timeoutCannotPromotePartialSpeechToSuccessfulAction() {
        val result = finish(completed = false, final = "", streamed = "Opening YouTube")
        assertFalse(result.success)
        assertEquals("Opening YouTube", result.text)
        assertTrue(result.error!!.contains("timed out"))
    }

    @Test fun serverErrorIsNotHiddenByPartialTextOrCompletedLatch() {
        val error = "Closed 1007: invalid payload"
        val result = finish(completed = true, final = "", streamed = "I will open", error = error)
        assertFalse(result.success)
        assertEquals(error, result.error)
    }

    @Test fun emptyCompletedReplyFails() {
        val result = finish(final = "  ", streamed = "")
        assertFalse(result.success)
        assertEquals("empty reply", result.error)
    }

    @Test fun punctuationSurvivesAssemblyAndNextTurnContextExactlyOnce() {
        val answer = "Yes, Dr. Rao: 3.14! Why? https://youtu.be/example?a=1&b=2; done."
        val result = finish(final = answer, streamed = answer)
        assertEquals(answer, result.text)
        assertFalse(result.text.contains('\n'))
        val envelope = JSONObject(GeminiLiveClient.buildConversationTurn(
            listOf("assistant" to result.text), "What was the value?"
        ))
        val prompt = envelope.getJSONObject("clientContent").getJSONArray("turns")
            .getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals(1, Regex(Regex.escape(answer)).findAll(prompt).count())
        assertTrue(prompt.endsWith("What was the value?"))
    }

    @Test fun streamedRouteEmitsFinalWithoutReplayingTextOrThoughts() {
        val events = mutableListOf<AgentStreamEvent>()
        UnifiedAssistantDispatcher.emitTurn(
            onEvent = { events.add(it) }, thinking = "Thought already streamed",
            answer = "Answer already streamed", model = "Live", latencyMs = 12,
            handled = true, contentAlreadyStreamed = true
        )
        assertEquals(0, events.filterIsInstance<AgentStreamEvent.TextDelta>().size)
        assertEquals(0, events.filterIsInstance<AgentStreamEvent.ThinkingDelta>().size)
        assertEquals(1, events.filterIsInstance<AgentStreamEvent.ThinkingDone>().size)
        assertEquals("Answer already streamed", events.filterIsInstance<AgentStreamEvent.Final>().single().text)
    }

    @Test fun rendererDoesNotTurnNetworkFragmentsIntoParagraphs() {
        val reply = com.pr4nav.jarvis.chat.StreamingReply()
        listOf("Hello", ",", " ", "Dr", ".", " Rao", ": ", "3", ".", "14", "!", "\n", "Next line.")
            .forEach { reply.append(it) }
        assertEquals("Hello, Dr. Rao: 3.14!\nNext line.", reply.snapshot())
        assertEquals(reply.snapshot(), reply.finish(""))
    }

    @Test fun rendererFinalReplacesPartialNarrationWithoutDuplicatingAnswer() {
        val reply = com.pr4nav.jarvis.chat.StreamingReply()
        reply.append("Looking it up…")
        reply.append("The result is 3.14.")
        assertEquals("The result is 3.14.", reply.finish("The result is 3.14."))
    }

    @Test fun modelPartsPreserveWhitespaceOnlyFragments() {
        val raw = """{"serverContent":{"modelTurn":{"parts":[{"text":"Hello"},{"text":" "},{"text":"there"},{"text":"\n"},{"text":"Next."}]}}}"""
        assertEquals("Hello there\nNext.", GeminiLiveClient.parseServerMessage(raw).textDelta)
    }

    @Test fun parsedLiveFragmentsReachRendererWithoutFormattingChanges() {
        val reply = com.pr4nav.jarvis.chat.StreamingReply()
        val expected = "A comma, a colon: a semicolon; and a URL https://youtu.be/example."
        for (fragment in expected.chunked(3)) {
            val raw = JSONObject().put("serverContent", JSONObject().put(
                "outputTranscription", JSONObject().put("text", fragment)
            )).toString()
            reply.append(GeminiLiveClient.parseServerMessage(raw).outputTranscript)
        }
        val result = finish(final = expected, streamed = reply.snapshot())
        assertTrue(result.success)
        assertEquals(expected, reply.snapshot())
        assertEquals(expected, reply.finish(result.text))
    }

    @Test fun nonStreamingRoutesStillEmitTheirAnswer() {
        val events = mutableListOf<AgentStreamEvent>()
        UnifiedAssistantDispatcher.emitTurn(
            onEvent = { events.add(it) }, thinking = "Reason", answer = "Answer",
            model = "Needle", latencyMs = 1, handled = true
        )
        assertEquals(1, events.filterIsInstance<AgentStreamEvent.TextDelta>().size)
        assertEquals(1, events.filterIsInstance<AgentStreamEvent.ThinkingDelta>().size)
        assertEquals(1, events.filterIsInstance<AgentStreamEvent.Final>().size)
    }
}
