package com.pr4nav.jarvis.router

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.llm.GeminiCloudLLM
import com.pr4nav.jarvis.llm.GroqClient
import com.pr4nav.jarvis.needle.NeedleExecutor
import com.pr4nav.jarvis.needle.NeedleRouteResult
import com.pr4nav.jarvis.needle.NeedleRouter
import com.pr4nav.jarvis.needle.RouteType
import com.pr4nav.jarvis.response.AnswerSynthesizer
import com.pr4nav.jarvis.response.JarvisResponse
import com.pr4nav.jarvis.response.UserResponseSanitizer
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolResult
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JARVIS Central Multi-Layer Auto Router & Orchestrator.
 *
 * Architecture:
 * USER INPUT -> FAST LOCAL INTENT CHECK -> TASK CLASSIFIER:
 * 1. DEVICE_COMMAND -> Needle Reflex -> (if fails) Qwen Translator -> (if fails) Cloud Reasoning -> Needle Executor
 * 2. CASUAL -> Local Qwen (compact context)
 * 3. GENERAL_KNOWLEDGE -> Qwen Local -> Confidence Check -> (if low confidence) Cloud LLM
 * 4. CODING -> Cloud / AGY Autonomous Coding Agent
 * 5. COMPLEX_REASONING -> Cloud / AGY Planning Engine
 */
object JarvisRouter {

    private const val TAG = "JarvisRouter"

    data class JarvisRoutingResult(
        val handled: Boolean,
        val category: TaskCategory,
        val routeSelected: String,
        val responseText: String,
        val speechText: String,
        val confidence: Float = 0.95f,
        val thinkingTrace: String = "",
        val modelEngine: String = "JARVIS Core",
        val toolResult: ToolResult? = null,
        val latencyMs: Long = 0L,
        val events: List<ActivityEvent> = emptyList()
    )

    /**
     * Classifies natural language input into one of the 5 explicit auto-router task categories.
     */
    fun classify(query: String): TaskClassification {
        val lower = query.trim().lowercase(Locale.ROOT)
        val normalized = LanguageNormalizer.cleanText(query)

        // 1. Coding Tasks
        val codingKeywords = listOf(
            "code", "function", "class ", "def ", "var ", "val ", "fun ", "import ",
            "python", "kotlin", "javascript", "typescript", "java ", "c++", "rust", "react", "vue", "angular", "flutter",
            "html", "css", "sql", "git ", "github", "refactor", "debug", "compile",
            "build.gradle", "npm", "pip ", "script", "regex", "algorithm", "opencode",
            "make me a", "make a react", "build an app", "create an app", "app that", "program that"
        )
        if (codingKeywords.any { lower.contains(it) } && !lower.startsWith("open ") && !lower.startsWith("call ")) {
            return TaskClassification(
                category = TaskCategory.CODING,
                confidence = 0.94f,
                reasoning = "Query contains coding, programming, or development instructions."
            )
        }

        // 2. Complex Reasoning Tasks
        val reasoningKeywords = listOf(
            "plan ", "planning", "step by step", "analyze project", "compare and contrast",
            "pros and cons", "evaluate architecture", "multi-step", "workflow", "strategy"
        )
        if (reasoningKeywords.any { lower.contains(it) }) {
            return TaskClassification(
                category = TaskCategory.COMPLEX_REASONING,
                confidence = 0.90f,
                reasoning = "Query requires multi-step planning or architectural reasoning."
            )
        }

        // 3. Casual / Conversational Tasks
        val cleanLower = lower.replace(Regex("[.,!?;:]"), "").trim()
        val casualExact = listOf(
            "hi", "hello", "hey", "sup", "yo", "good morning", "good evening", "good afternoon",
            "how are you", "who are you", "what's up", "what is your name", "tell me a joke",
            "thank you", "thanks", "bye", "goodbye", "see you", "namaste", "kaise ho",
            "hello good morning", "hi good morning", "hey good morning"
        )
        if (casualExact.contains(cleanLower) || cleanLower.startsWith("hi ") || cleanLower.startsWith("hey ") || cleanLower.startsWith("hello ")) {
            return TaskClassification(
                category = TaskCategory.CASUAL,
                confidence = 0.98f,
                reasoning = "Everyday greeting or companion conversation."
            )
        }

        // 4. General Knowledge / Facts / Informational
        if (LanguageNormalizer.isInformational(query) ||
            lower.startsWith("what is ") || lower.startsWith("who is ") || lower.startsWith("why is ") ||
            lower.startsWith("how does ") || lower.startsWith("explain ") || lower.startsWith("tell me about ") ||
            lower.startsWith("when was ") || lower.startsWith("where is ")) {
            return TaskClassification(
                category = TaskCategory.GENERAL_KNOWLEDGE,
                confidence = 0.92f,
                reasoning = "Informational / General Knowledge inquiry."
            )
        }

        // 5. Device Command Tasks (Default for action verbs and device keywords)
        val deviceKeywords = listOf(
            "bluetooth", "wifi", "wi-fi", "torch", "flashlight", "volume", "sound", "mute",
            "brightness", "battery", "screenshot", "call", "dial", "message", "sms", "whatsapp",
            "open", "launch", "close", "stop", "kill", "settings", "navigate", "directions",
            "camera", "timer", "alarm", "file", "download", "run ", "termux"
        )
        if (deviceKeywords.any { lower.contains(it) } || normalized.contains("khol") || normalized.contains("band") || normalized.contains("chalu")) {
            return TaskClassification(
                category = TaskCategory.DEVICE_COMMAND,
                confidence = 0.95f,
                reasoning = "On-device action, hardware control, or app navigation."
            )
        }

        // Fallback: General casual / conversational
        return TaskClassification(
            category = TaskCategory.CASUAL,
            confidence = 0.70f,
            reasoning = "Default conversational query."
        )
    }

    /**
     * Routes and executes query across the multi-layer architecture.
     */
    fun route(
        context: Context,
        input: String,
        onActivity: ((ActivityEvent) -> Unit)? = null,
        onChunk: ((String) -> Unit)? = null,
        onResult: (JarvisRoutingResult) -> Unit
    ) {
        val t0 = System.currentTimeMillis()
        val events = mutableListOf<ActivityEvent>()

        fun emit(state: ActivityState, detail: String) {
            val ev = ActivityEvent(state, detail)
            events.add(ev)
            onActivity?.invoke(ev)
        }

        val resolvedQuery = com.pr4nav.jarvis.context.ConversationalContext.resolvePronouns(input.trim())
        if (resolvedQuery.isEmpty()) {
            emit(ActivityState.DONE, "Ready")
            val emptyMsg = "Yes? How can I help you?"
            onResult(
                JarvisRoutingResult(
                    handled = false,
                    category = TaskCategory.CASUAL,
                    routeSelected = "LOCAL_EMPTY",
                    responseText = emptyMsg,
                    speechText = emptyMsg,
                    latencyMs = System.currentTimeMillis() - t0,
                    events = events
                )
            )
            return
        }

        emit(ActivityState.UNDERSTANDING, "Analyzing query intent…")
        CanonicalToolRegistry.init(context)
        com.pr4nav.jarvis.workspace.JarvisWorkspace.initWorkspace(context)

        // 0a. Multi-Stage Pre-Routing Filters (Existing Tool First, Safety, Workspace)
        val preDecision = PreRoutingPipeline.filter(context, resolvedQuery)
        if (preDecision is PreRoutingDecision.Blocked) {
            emit(ActivityState.DONE, "Command blocked by security policy")
            val latency = System.currentTimeMillis() - t0
            val res = JarvisRoutingResult(
                handled = false,
                category = TaskCategory.DEVICE_COMMAND,
                routeSelected = "SECURITY_BLOCKED",
                responseText = preDecision.reason,
                speechText = preDecision.reason,
                thinkingTrace = "<think>\n• Input: \"$resolvedQuery\"\n• Guard: ${preDecision.reason}\n</think>",
                modelEngine = "JARVIS CmdGuard",
                latencyMs = latency,
                events = events
            )
            onChunk?.invoke(preDecision.reason)
            onResult(res)
            return
        }

        if (preDecision is PreRoutingDecision.DirectToolMatch) {
            val tool = preDecision.toolName
            val args = preDecision.arguments
            emit(ActivityState.EXECUTING, "Executing [$tool] via Direct Reflex…")

            val finalOutput: String
            val isSuccess: Boolean
            val toolRes: ToolResult?

            if (tool == "jarvis_environment") {
                finalOutput = if (args.optString("query") == "capabilities") {
                    com.pr4nav.jarvis.tools.ToolCapabilityRegistry.getCapabilitiesSummary(context)
                } else {
                    com.pr4nav.jarvis.environment.JarvisEnvironment.getSnapshot(context).toFormattedReport()
                }
                isSuccess = true
                toolRes = ToolResult.ok(JSONObject().put("report", finalOutput))
            } else {
                val validation = com.pr4nav.jarvis.tools.ToolValidator.validate(context, tool, args, resolvedQuery)
                if (validation is com.pr4nav.jarvis.tools.ValidationResult.Valid) {
                    toolRes = CanonicalToolRegistry.execute(context, tool, args)
                    isSuccess = toolRes.success
                    if (isSuccess) {
                        com.pr4nav.jarvis.context.ContextManager.updateToolContext(tool, args)
                    }
                    val synthesized = AnswerSynthesizer.synthesize(
                        resolvedQuery,
                        tool,
                        toolRes.data as? JSONObject,
                        AnswerSynthesizer.determineResponseMode(resolvedQuery, "DEVICE")
                    )
                    finalOutput = synthesized
                } else {
                    val rej = validation as com.pr4nav.jarvis.tools.ValidationResult.Rejected
                    finalOutput = rej.error.message
                    isSuccess = false
                    toolRes = ToolResult.failure(rej.reasonCode, rej.error.message)
                }
            }

            emit(ActivityState.DONE, "Direct tool execution completed")
            val latency = System.currentTimeMillis() - t0
            val sanitized = UserResponseSanitizer.sanitize(finalOutput, resolvedQuery)
            val speech = UserResponseSanitizer.sanitizeForSpeech(finalOutput, resolvedQuery)

            val trace = "<think>\n• Input: \"$resolvedQuery\"\n• Router: Direct Existing Tool Match [$tool]\n• Reason: ${preDecision.reason}\n• Latency: ${latency}ms\n</think>"
            val res = JarvisRoutingResult(
                handled = isSuccess,
                category = TaskCategory.DEVICE_COMMAND,
                routeSelected = "DIRECT_EXISTING_TOOL",
                responseText = sanitized,
                speechText = speech,
                thinkingTrace = trace,
                modelEngine = "JARVIS Direct Capability Layer",
                toolResult = toolRes,
                latencyMs = latency,
                events = events
            )
            RouterDiagnostics.record(
                RouterDiagnosticTrace(
                    input = resolvedQuery,
                    category = TaskCategory.DEVICE_COMMAND,
                    classificationConfidence = preDecision.confidence,
                    routeSelected = "DIRECT_EXISTING_TOOL",
                    modelEngine = "JARVIS Direct Capability Layer",
                    toolRequested = tool,
                    toolArguments = args.toString(),
                    executionResult = toolRes?.status?.name ?: "OK",
                    finalResponse = sanitized,
                    latencyMs = latency,
                    events = events
                )
            )
            onChunk?.invoke(sanitized)
            onResult(res)
            return
        }

        val classification = if (preDecision is PreRoutingDecision.ModelRoute) {
            preDecision.classification
        } else {
            classify(resolvedQuery)
        }

        // 0b. Fast Local Intent Check: Math & Deterministic Answers (<5ms)
        val directClassified = com.pr4nav.jarvis.intent.IntentClassifier.classify(resolvedQuery)
        if (directClassified.responseType == com.pr4nav.jarvis.intent.ResponseType.ANSWER && directClassified.directAnswer != null) {
            val answer = directClassified.directAnswer
            emit(ActivityState.DONE, "Deterministic response ready")
            val latency = System.currentTimeMillis() - t0
            val sanitized = UserResponseSanitizer.sanitize(answer, resolvedQuery)
            val speech = UserResponseSanitizer.sanitizeForSpeech(answer, resolvedQuery)
            val trace = "<think>\n• Input: \"$resolvedQuery\"\n• Classification: ${classification.category.name}\n• Router: Fast Local Deterministic Answer\n• Latency: ${latency}ms\n</think>"

            val res = JarvisRoutingResult(
                handled = true,
                category = classification.category,
                routeSelected = "FAST_LOCAL_INTENT",
                responseText = sanitized,
                speechText = speech,
                thinkingTrace = trace,
                modelEngine = "JARVIS Deterministic Engine",
                latencyMs = latency,
                events = events
            )
            RouterDiagnostics.record(
                RouterDiagnosticTrace(
                    input = resolvedQuery,
                    category = classification.category,
                    classificationConfidence = classification.confidence,
                    routeSelected = "FAST_LOCAL_INTENT",
                    modelEngine = "JARVIS Deterministic Engine",
                    finalResponse = sanitized,
                    latencyMs = latency,
                    events = events
                )
            )
            onChunk?.invoke(sanitized)
            onResult(res)
            return
        }

        // =========================================================================
        // 1. DEVICE_COMMAND Pipeline
        // =========================================================================
        if (classification.category == TaskCategory.DEVICE_COMMAND) {
            emit(ActivityState.CHECKING, "Evaluating device capabilities…")
            routeDeviceCommand(
                context = context,
                query = resolvedQuery,
                classification = classification,
                t0 = t0,
                events = events,
                emit = ::emit,
                onChunk = onChunk,
                onResult = onResult
            )
            return
        }

        // =========================================================================
        // 2. CODING & COMPLEX_REASONING Pipeline -> Cloud / AGY Agent
        // =========================================================================
        if (classification.category == TaskCategory.CODING || classification.category == TaskCategory.COMPLEX_REASONING) {
            emit(ActivityState.PLANNING, "Engaging Cloud/AGY Reasoning Engine…")
            routeCloudReasoning(
                context = context,
                query = resolvedQuery,
                classification = classification,
                t0 = t0,
                events = events,
                emit = ::emit,
                onChunk = onChunk,
                onResult = onResult
            )
            return
        }

        // =========================================================================
        // 3. CASUAL Conversation Pipeline -> Groq LLaMA (compact context)
        // =========================================================================
        if (classification.category == TaskCategory.CASUAL) {
            emit(ActivityState.THINKING, "Connecting to Groq LLaMA Engine…")
            routeCasualGroq(
                context = context,
                query = resolvedQuery,
                classification = classification,
                t0 = t0,
                events = events,
                emit = ::emit,
                onChunk = onChunk,
                onResult = onResult
            )
            return
        }

        // =========================================================================
        // 4. GENERAL_KNOWLEDGE Pipeline -> Qwen first with Confidence Check
        // =========================================================================
        emit(ActivityState.SEARCHING, "Checking factual knowledge…")
        routeGeneralKnowledge(
            context = context,
            query = resolvedQuery,
            classification = classification,
            t0 = t0,
            events = events,
            emit = ::emit,
            onChunk = onChunk,
            onResult = onResult
        )
    }

    /**
     * Device Command Pipeline:
     * Step 1: Needle / LanguageNormalizer deterministic reflex (<15ms)
     * Step 2 (if unresolved): Qwen Command Translator fallback
     * Step 3 (if still unresolved): Cloud reasoning fallback
     */
    private fun routeDeviceCommand(
        context: Context,
        query: String,
        classification: TaskClassification,
        t0: Long,
        events: MutableList<ActivityEvent>,
        emit: (ActivityState, String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onResult: (JarvisRoutingResult) -> Unit
    ) {
        // Step 1: Fast deterministic language normalizer
        val normalized = LanguageNormalizer.normalize(query)
        if (normalized != null && normalized.confidence >= 0.85f) {
            emit(ActivityState.EXECUTING, "Executing [${normalized.tool}] via Needle Reflex…")
            try {
                val toolRes = CanonicalToolRegistry.execute(context, normalized.tool, normalized.args)
                if (toolRes.success) {
                    com.pr4nav.jarvis.context.ContextManager.updateToolContext(normalized.tool, normalized.args)
                } else {
                    com.pr4nav.jarvis.context.ConversationalContext.updateContext(normalized.tool, normalized.args)
                }
                val synthesized = AnswerSynthesizer.synthesize(query, normalized.tool, toolRes.data as? JSONObject, AnswerSynthesizer.determineResponseMode(query, classification.category.name))
                val sanitized = UserResponseSanitizer.sanitize(synthesized, query)
                val speech = UserResponseSanitizer.sanitizeForSpeech(synthesized, query)
                emit(ActivityState.DONE, "Device command completed")

                val latency = System.currentTimeMillis() - t0
                val trace = "<think>\n• Input: \"$query\"\n• Classification: DEVICE_COMMAND\n• Step: Needle Deterministic Reflex\n• Tool: ${normalized.tool}\n• Args: ${normalized.args}\n• Latency: ${latency}ms\n</think>"

                val res = JarvisRoutingResult(
                    handled = toolRes.success,
                    category = TaskCategory.DEVICE_COMMAND,
                    routeSelected = "NEEDLE_DETERMINISTIC",
                    responseText = sanitized,
                    speechText = speech,
                    thinkingTrace = trace,
                    modelEngine = "Needle Reflex Executor",
                    toolResult = toolRes,
                    latencyMs = latency,
                    events = events
                )
                RouterDiagnostics.record(
                    RouterDiagnosticTrace(
                        input = query,
                        category = TaskCategory.DEVICE_COMMAND,
                        classificationConfidence = classification.confidence,
                        routeSelected = "NEEDLE_DETERMINISTIC",
                        modelEngine = "Needle Reflex Executor",
                        toolRequested = normalized.tool,
                        toolArguments = normalized.args.toString(),
                        executionResult = toolRes.status.name,
                        finalResponse = sanitized,
                        latencyMs = latency,
                        events = events
                    )
                )
                onChunk?.invoke(sanitized)
                onResult(res)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Needle deterministic execution failed, falling back to Groq Translator: ${e.message}")
            }
        }

        // Step 2: Fallback to Groq Command Translator
        emit(ActivityState.THINKING, "Needle unresolved; converting via Groq Command Translator…")
        GroqCommandTranslator.translate(
            context = context,
            userRequest = query,
            onSuccess = { translatedCall ->
                emit(ActivityState.EXECUTING, "Executing translated command [${translatedCall.tool}]…")
                val toolRes = CanonicalToolRegistry.execute(context, translatedCall.tool, translatedCall.args)
                if (toolRes.success) {
                    com.pr4nav.jarvis.context.ContextManager.updateToolContext(translatedCall.tool, translatedCall.args)
                }
                val synthesized = AnswerSynthesizer.synthesize(query, translatedCall.tool, toolRes.data as? JSONObject, AnswerSynthesizer.determineResponseMode(query, classification.category.name))
                val sanitized = UserResponseSanitizer.sanitize(synthesized, query)
                val speech = UserResponseSanitizer.sanitizeForSpeech(synthesized, query)
                emit(ActivityState.DONE, "Command executed via translator")

                val latency = System.currentTimeMillis() - t0
                val trace = "<think>\n• Input: \"$query\"\n• Step: Groq Command Translator\n• Translated Command: ${translatedCall.tool}(${translatedCall.args})\n• Executor: CanonicalToolRegistry\n• Latency: ${latency}ms\n</think>"

                val res = JarvisRoutingResult(
                    handled = toolRes.success,
                    category = TaskCategory.DEVICE_COMMAND,
                    routeSelected = "GROQ_TRANSLATOR_NEEDLE",
                    responseText = sanitized,
                    speechText = speech,
                    thinkingTrace = trace,
                    modelEngine = "Groq Translator + Needle",
                    toolResult = toolRes,
                    latencyMs = latency,
                    events = events
                )
                RouterDiagnostics.record(
                    RouterDiagnosticTrace(
                        input = query,
                        category = TaskCategory.DEVICE_COMMAND,
                        classificationConfidence = classification.confidence,
                        routeSelected = "GROQ_TRANSLATOR_NEEDLE",
                        modelEngine = "Groq Translator + Needle",
                        toolRequested = translatedCall.tool,
                        toolArguments = translatedCall.args.toString(),
                        executionResult = toolRes.status.name,
                        finalResponse = sanitized,
                        latencyMs = latency,
                        events = events,
                        fallbackUsed = true,
                        fallbackReason = "Needle intent unresolved; translated via Groq"
                    )
                )
                onChunk?.invoke(sanitized)
                onResult(res)
            },
            onError = { translatorErr ->
                Log.w(TAG, "Groq translator failed: $translatorErr; escalating to Cloud Reasoning fallback")
                emit(ActivityState.PLANNING, "Translating via Cloud Reasoning fallback…")
                routeCloudReasoning(context, query, classification, t0, events, emit, onChunk, onResult)
            }
        )
    }

    /**
     * Casual Conversation Pipeline -> Groq LLaMA (fast edge context).
     */
    private fun routeCasualGroq(
        context: Context,
        query: String,
        classification: TaskClassification,
        t0: Long,
        events: MutableList<ActivityEvent>,
        emit: (ActivityState, String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onResult: (JarvisRoutingResult) -> Unit
    ) {
        val history = com.pr4nav.jarvis.context.ConversationalContext.getRecentTurns(2)
        emit(ActivityState.THINKING, "Generating response via Groq…")

        GroqClient.query(
            context = context,
            prompt = query,
            history = history,
            onSuccess = { groqRes ->
                emit(ActivityState.DONE, "Response generated")
                val latency = System.currentTimeMillis() - t0
                val sanitized = UserResponseSanitizer.sanitize(groqRes.response, query)
                val speech = UserResponseSanitizer.sanitizeForSpeech(sanitized, query)
                com.pr4nav.jarvis.context.ConversationalContext.recordTurn(query, sanitized)

                val trace = if (groqRes.thinkingTrace.isNotBlank()) {
                    "<think>\n${groqRes.thinkingTrace}\n• Category: CASUAL\n• Engine: Groq (${groqRes.modelUsed})\n• Latency: ${latency}ms\n</think>"
                } else {
                    "<think>\n• Input: \"$query\"\n• Category: CASUAL\n• Engine: Groq (${groqRes.modelUsed})\n• Latency: ${latency}ms\n</think>"
                }
                val res = JarvisRoutingResult(
                    handled = true,
                    category = TaskCategory.CASUAL,
                    routeSelected = "GROQ_CASUAL",
                    responseText = sanitized,
                    speechText = speech,
                    thinkingTrace = trace,
                    modelEngine = "Groq ${groqRes.modelUsed}",
                    latencyMs = latency,
                    events = events
                )
                RouterDiagnostics.record(
                    RouterDiagnosticTrace(
                        input = query,
                        category = TaskCategory.CASUAL,
                        classificationConfidence = classification.confidence,
                        routeSelected = "GROQ_CASUAL",
                        modelEngine = "Groq ${groqRes.modelUsed}",
                        finalResponse = sanitized,
                        latencyMs = latency,
                        events = events
                    )
                )
                onChunk?.invoke(sanitized)
                onResult(res)
            },
            onError = { groqErr ->
                Log.w(TAG, "Groq unavailable for casual query, escalating to Cloud: $groqErr")
                emit(ActivityState.PLANNING, "Groq offline; using Cloud conversational fallback…")
                routeCloudReasoning(context, query, classification, t0, events, emit, onChunk, onResult)
            }
        )
    }

    /**
     * General Knowledge Pipeline:
     * Groq first -> Confidence check -> Escalates to Cloud/AGY if needed.
     */
    private fun routeGeneralKnowledge(
        context: Context,
        query: String,
        classification: TaskClassification,
        t0: Long,
        events: MutableList<ActivityEvent>,
        emit: (ActivityState, String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onResult: (JarvisRoutingResult) -> Unit
    ) {
        emit(ActivityState.THINKING, "Consulting Groq LLaMA…")
        GroqClient.query(
            context = context,
            prompt = query,
            onSuccess = { groqRes ->
                val answer = groqRes.response.trim()

                if (answer.isNotBlank()) {
                    emit(ActivityState.DONE, "Answer completed")
                    val latency = System.currentTimeMillis() - t0
                    val sanitized = UserResponseSanitizer.sanitize(answer, query)
                    val speech = UserResponseSanitizer.sanitizeForSpeech(sanitized, query)
                    com.pr4nav.jarvis.context.ConversationalContext.recordTurn(query, sanitized)

                    val trace = if (groqRes.thinkingTrace.isNotBlank()) {
                        "<think>\n${groqRes.thinkingTrace}\n• Category: GENERAL_KNOWLEDGE\n• Engine: Groq (${groqRes.modelUsed})\n• Latency: ${latency}ms\n</think>"
                    } else {
                        "<think>\n• Input: \"$query\"\n• Category: GENERAL_KNOWLEDGE\n• Engine: Groq (${groqRes.modelUsed})\n• Latency: ${latency}ms\n</think>"
                    }
                    val res = JarvisRoutingResult(
                        handled = true,
                        category = TaskCategory.GENERAL_KNOWLEDGE,
                        routeSelected = "GROQ_GK_SUCCESS",
                        responseText = sanitized,
                        speechText = speech,
                        thinkingTrace = trace,
                        modelEngine = "Groq ${groqRes.modelUsed}",
                        latencyMs = latency,
                        events = events
                    )
                    RouterDiagnostics.record(
                        RouterDiagnosticTrace(
                            input = query,
                            category = TaskCategory.GENERAL_KNOWLEDGE,
                            classificationConfidence = 0.90f,
                            routeSelected = "GROQ_GK_SUCCESS",
                            modelEngine = "Groq ${groqRes.modelUsed}",
                            finalResponse = sanitized,
                            latencyMs = latency,
                            events = events
                        )
                    )
                    onChunk?.invoke(sanitized)
                    onResult(res)
                } else {
                    emit(ActivityState.SEARCHING, "Groq response empty, sequential fallback to Cloud…")
                    routeCloudReasoning(context, query, classification, t0, events, emit, onChunk, onResult)
                }
            },
            onError = { err ->
                Log.w(TAG, "Groq unavailable for general knowledge: $err")
                emit(ActivityState.PLANNING, "Groq unreachable, escalating to Cloud reasoning…")
                routeCloudReasoning(context, query, classification, t0, events, emit, onChunk, onResult)
            }
        )
    }

    /**
     * Heuristic confidence check for model answers.
     * Detects uncertainty phrases, "I don't know", conflicting statements, and evasive answers.
     */
    fun isLowConfidenceAnswer(answer: String): Boolean {
        val lower = answer.lowercase(Locale.ROOT)
        if (lower.length < 15) return true

        val uncertaintyPhrases = listOf(
            "i am not sure", "i'm not sure", "i do not know", "i don't know",
            "it is not certain", "cannot be determined", "as an ai i cannot",
            "i am unable to answer", "i have no information", "it might be",
            "conflicting reports", "unsure", "not enough context"
        )
        return uncertaintyPhrases.any { lower.contains(it) }
    }

    /**
     * Cloud / AGY Reasoning Pipeline.
     */
    private fun routeCloudReasoning(
        context: Context,
        query: String,
        classification: TaskClassification,
        t0: Long,
        events: MutableList<ActivityEvent>,
        emit: (ActivityState, String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onResult: (JarvisRoutingResult) -> Unit
    ) {
        emit(ActivityState.THINKING, "Querying Cloud/AGY Reasoning Engine…")

        val envHeader = com.pr4nav.jarvis.environment.JarvisEnvironment.getAgentContextHeader(context)
        val fullPrompt = "$envHeader\n\nUser Request:\n$query"

        GeminiCloudLLM.generate(
            context = context,
            prompt = fullPrompt,
            onChunk = { chunk ->
                emit(ActivityState.WAITING_FOR_RESULT, "Receiving response tokens…")
                onChunk?.invoke(chunk)
            },
            onSuccess = { cloudResponse ->
                emit(ActivityState.DONE, "Response complete")
                val latency = System.currentTimeMillis() - t0
                val sanitized = UserResponseSanitizer.sanitize(cloudResponse, query)
                val speech = UserResponseSanitizer.sanitizeForSpeech(sanitized, query)
                com.pr4nav.jarvis.context.ConversationalContext.recordTurn(query, sanitized)

                val trace = "<think>\n• Input: \"$query\"\n• Classification: ${classification.category.name}\n• Engine: Google Gemini 2.0 Flash / AGY Cloud\n• Latency: ${latency}ms\n</think>"
                val res = JarvisRoutingResult(
                    handled = true,
                    category = classification.category,
                    routeSelected = "CLOUD_AGY_REASONING",
                    responseText = sanitized,
                    speechText = speech,
                    thinkingTrace = trace,
                    modelEngine = "Gemini 2.0 Flash (Cloud)",
                    latencyMs = latency,
                    events = events
                )
                RouterDiagnostics.record(
                    RouterDiagnosticTrace(
                        input = query,
                        category = classification.category,
                        classificationConfidence = classification.confidence,
                        routeSelected = "CLOUD_AGY_REASONING",
                        modelEngine = "Gemini 2.0 Flash (Cloud)",
                        finalResponse = sanitized,
                        latencyMs = latency,
                        events = events
                    )
                )
                onResult(res)
            },
            onError = { errMsg ->
                emit(ActivityState.DONE, "Fallback completed")
                val latency = System.currentTimeMillis() - t0
                val fallbackText = getSystemFallback(query)
                val sanitized = UserResponseSanitizer.sanitize(fallbackText, query)
                val speech = UserResponseSanitizer.sanitizeForSpeech(sanitized, query)

                val trace = "<think>\n• Input: \"$query\"\n• Error: $errMsg\n• Fallback: System Native Assistant\n• Latency: ${latency}ms\n</think>"
                val res = JarvisRoutingResult(
                    handled = false,
                    category = classification.category,
                    routeSelected = "SYSTEM_NATIVE_FALLBACK",
                    responseText = sanitized,
                    speechText = speech,
                    thinkingTrace = trace,
                    modelEngine = "System Fallback",
                    latencyMs = latency,
                    events = events
                )
                RouterDiagnostics.record(
                    RouterDiagnosticTrace(
                        input = query,
                        category = classification.category,
                        classificationConfidence = classification.confidence,
                        routeSelected = "SYSTEM_NATIVE_FALLBACK",
                        modelEngine = "System Fallback",
                        finalResponse = sanitized,
                        latencyMs = latency,
                        events = events,
                        fallbackUsed = true,
                        fallbackReason = errMsg
                    )
                )
                onChunk?.invoke(sanitized)
                onResult(res)
            }
        )
    }

    private fun getSystemFallback(query: String): String {
        val lower = query.lowercase(Locale.ROOT)
        return when {
            lower.contains("who are you") || lower.contains("what is your name") ->
                "I am JARVIS, your personal autonomous AI assistant."
            lower.contains("how are you") ->
                "All systems are operating at peak performance, sir. How can I assist you today?"
            lower.contains("hi") || lower.contains("hello") || lower.contains("hey") ->
                "Hello! Systems online and ready. What would you like to do?"
            lower.contains("time") ->
                "The current time is ${java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())}."
            lower.contains("date") ->
                "Today is ${java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()).format(java.util.Date())}."
            else ->
                "I am ready. You can ask me to open apps, control volume, toggle flashlight, control Bluetooth, run commands, or search information."
        }
    }
}
