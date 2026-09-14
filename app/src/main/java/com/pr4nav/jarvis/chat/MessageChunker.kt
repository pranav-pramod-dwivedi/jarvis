package com.pr4nav.jarvis.chat

/**
 * Splits a finished answer into chat-sized bubbles at paragraph boundaries,
 * keeping fenced code blocks atomic. Short replies stay a single bubble;
 * long answers arrive as a burst of messages like Muse / OpenClaw.
 */
object MessageChunker {

    fun chunk(text: String, maxChars: Int = 420): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= maxChars && !clean.contains("```")) return listOf(clean)

        // Split into atomic units: fenced blocks stay whole, rest splits on blank lines.
        val units = mutableListOf<String>()
        val fence = Regex("```[\\s\\S]*?(?:```|$)")
        var last = 0
        for (m in fence.findAll(clean)) {
            val before = clean.substring(last, m.range.first).trim()
            if (before.isNotBlank()) {
                before.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
                    .forEach { units.add(it) }
            }
            units.add("\u0000FENCE\u0000" + m.value.trim())
            last = m.range.last + 1
        }
        val tail = clean.substring(last).trim()
        if (tail.isNotBlank()) {
            tail.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
                .forEach { units.add(it) }
        }

        val chunks = mutableListOf<String>()
        val cur = StringBuilder()
        fun flush() {
            val s = cur.toString().trim()
            if (s.isNotBlank()) chunks.add(s.replace("\u0000FENCE\u0000", ""))
            cur.clear()
        }
        for (u in units) {
            val isFence = u.startsWith("\u0000FENCE\u0000")
            val body = if (isFence) u.removePrefix("\u0000FENCE\u0000") else u
            if (isFence) {
                flush()
                chunks.add(body)
                continue
            }
            if (body.length > maxChars) {
                flush()
                // Split long paragraphs on sentence boundaries.
                val sentences = body.split(Regex("(?<=[.!?])\\s+"))
                val acc = StringBuilder()
                for (s in sentences) {
                    if (acc.length + s.length + 1 > maxChars && acc.isNotEmpty()) {
                        chunks.add(acc.toString().trim())
                        acc.clear()
                    }
                    if (acc.isNotEmpty()) acc.append(' ')
                    acc.append(s)
                }
                if (acc.isNotEmpty()) chunks.add(acc.toString().trim())
                continue
            }
            if (cur.isNotEmpty() && cur.length + body.length + 2 > maxChars) flush()
            if (cur.isNotEmpty()) cur.append("\n\n")
            cur.append(body)
        }
        flush()
        return chunks.filter { it.isNotBlank() }.ifEmpty { listOf(clean.take(maxChars)) }
    }
}
