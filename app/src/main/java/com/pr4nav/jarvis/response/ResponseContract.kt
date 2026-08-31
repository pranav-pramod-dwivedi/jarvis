package com.pr4nav.jarvis.response

/**
 * Declares the intended response mode for a user request.
 * Disentangles capability invocation (e.g. search_web) from final response delivery.
 */
enum class ResponseMode {
    /** Direct factual, mathematical, or calculated answer */
    ANSWER,

    /** Direct physical device action (e.g. toggle flashlight, adjust volume) */
    ACTION,

    /** Information retrieval tool execution followed by natural language answer synthesis */
    SEARCH_THEN_ANSWER,

    /** User explicitly requested to view or browse search results */
    SEARCH_ONLY,

    /** Ambiguous query or missing parameters requiring user clarification */
    CLARIFICATION,

    /** Fallback or unhandled query */
    UNKNOWN
}

/**
 * Terminal execution status contract.
 * Invariant: Requests MUST terminate in one of these states. Intermediate states (e.g. TOOL_ACCEPTED)
 * are not valid termination states.
 */
enum class TerminationStatus {
    /** A complete, natural human answer has been synthesized and delivered to the user */
    FINAL_ANSWER,

    /** A physical device action has been executed and confirmed */
    ACTION_COMPLETED,

    /** Clarification from the user is required before execution can proceed */
    CLARIFICATION_REQUIRED,

    /** The requested capability or tool backend is unavailable on the device */
    CAPABILITY_UNAVAILABLE,

    /** The execution failed or encountered an unrecoverable error */
    EXECUTION_FAILED
}

/**
 * Declares the primary purpose of a registered canonical tool.
 */
enum class ToolPurpose {
    /** Physical state change on the device or external service */
    ACTION,

    /** Information or data retrieval for subsequent answer synthesis */
    RETRIEVAL,

    /** Local deterministic calculation or evaluation */
    COMPUTATION,

    /** Sensor, telemetry, or UI state capture */
    OBSERVATION,

    /** Data transformation or formatting */
    TRANSFORMATION
}
