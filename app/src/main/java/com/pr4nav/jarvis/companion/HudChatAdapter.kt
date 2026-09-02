package com.pr4nav.jarvis.companion

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pr4nav.jarvis.R
import com.pr4nav.jarvis.session.SessionMessage

class HudChatAdapter(
    private val messages: MutableList<SessionMessage> = mutableListOf()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_AGENT = 2

        private val THINK_REGEX = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)
        private val LATENCY_REGEX = Regex("Latency:\\s*(\\d+)\\s*ms", RegexOption.IGNORE_CASE)
    }

    // Set of message keys that have their thought section expanded (empty by default)
    private val expandedThoughts = mutableSetOf<String>()

    // Real-time TTS word-by-word highlight state
    private var activeSpeakingKey: String? = null
    private var activeSpeakingStart: Int = -1
    private var activeSpeakingEnd: Int = -1

    fun setMessages(newMessages: List<SessionMessage>) {
        messages.clear()
        expandedThoughts.clear()
        activeSpeakingKey = null
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun addMessage(message: SessionMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(message: SessionMessage) {
        if (messages.isNotEmpty()) {
            messages[messages.size - 1] = message
            notifyItemChanged(messages.size - 1)
        } else {
            addMessage(message)
        }
    }

    fun setSpeakingHighlight(msgKey: String, start: Int, end: Int) {
        activeSpeakingKey = msgKey
        activeSpeakingStart = start
        activeSpeakingEnd = end
        val pos = messages.indexOfLast { (if (it.id.isNotBlank()) it.id else "pos_${messages.indexOf(it)}") == msgKey }
        if (pos != -1) {
            notifyItemChanged(pos)
        } else {
            notifyDataSetChanged()
        }
    }

    fun clearSpeakingHighlight() {
        val oldKey = activeSpeakingKey
        activeSpeakingKey = null
        activeSpeakingStart = -1
        activeSpeakingEnd = -1
        if (oldKey != null) {
            val pos = messages.indexOfLast { (if (it.id.isNotBlank()) it.id else "pos_${messages.indexOf(it)}") == oldKey }
            if (pos != -1) {
                notifyItemChanged(pos)
                return
            }
        }
        notifyDataSetChanged()
    }

    fun clear() {
        messages.clear()
        expandedThoughts.clear()
        activeSpeakingKey = null
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = messages.size

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].sender.equals("user", ignoreCase = true)) {
            VIEW_TYPE_USER
        } else {
            VIEW_TYPE_AGENT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = inflater.inflate(R.layout.item_hud_user_message, parent, false)
            UserViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_hud_agent_message, parent, false)
            AgentViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        if (holder is UserViewHolder) {
            holder.bind(msg)
        } else if (holder is AgentViewHolder) {
            val msgKey = if (msg.id.isNotBlank()) msg.id else "pos_$position"
            val isExpanded = expandedThoughts.contains(msgKey)
            val isSpeaking = (msgKey == activeSpeakingKey)
            holder.bind(
                message = msg,
                isThoughtExpanded = isExpanded,
                isSpeaking = isSpeaking,
                speakingStart = if (isSpeaking) activeSpeakingStart else -1,
                speakingEnd = if (isSpeaking) activeSpeakingEnd else -1
            ) {
                if (expandedThoughts.contains(msgKey)) {
                    expandedThoughts.remove(msgKey)
                } else {
                    expandedThoughts.add(msgKey)
                }
                notifyItemChanged(position)
            }
        }
    }

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvText: TextView = itemView.findViewById(R.id.tv_user_text)

        fun bind(message: SessionMessage) {
            tvText.text = message.text
        }
    }

    class AgentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val layoutToolBadge: View = itemView.findViewById(R.id.layout_tool_badge)
        private val tvToolName: TextView = itemView.findViewById(R.id.tv_tool_name)
        private val layoutThoughtPill: View = itemView.findViewById(R.id.layout_thought_pill)
        private val tvThoughtSummary: TextView = itemView.findViewById(R.id.tv_thought_summary)
        private val ivThoughtChevron: ImageView = itemView.findViewById(R.id.iv_thought_chevron)
        private val tvThoughtContent: TextView = itemView.findViewById(R.id.tv_thought_content)
        private val tvAgentText: TextView = itemView.findViewById(R.id.tv_agent_text)

        fun bind(
            message: SessionMessage,
            isThoughtExpanded: Boolean,
            isSpeaking: Boolean = false,
            speakingStart: Int = -1,
            speakingEnd: Int = -1,
            onToggleThought: () -> Unit
        ) {
            // 1. Detect & Parse Tool Execution
            val toolName = extractToolName(message)
            if (!toolName.isNullOrBlank()) {
                layoutToolBadge.visibility = View.VISIBLE
                tvToolName.text = "Executed: $toolName"
            } else {
                layoutToolBadge.visibility = View.GONE
            }

            // 2. Detect & Parse <think>...</think> block
            val (thoughtText, cleanText) = extractThinking(message)
            if (!thoughtText.isNullOrBlank()) {
                layoutThoughtPill.visibility = View.VISIBLE
                
                // Calculate seconds or latency
                val durationText = calculateThoughtDuration(thoughtText)
                tvThoughtSummary.text = durationText
                
                tvThoughtContent.text = thoughtText
                tvThoughtContent.visibility = if (isThoughtExpanded) View.VISIBLE else View.GONE
                ivThoughtChevron.rotation = if (isThoughtExpanded) 180f else 0f

                layoutThoughtPill.setOnClickListener {
                    onToggleThought()
                }
            } else {
                layoutThoughtPill.visibility = View.GONE
                tvThoughtContent.visibility = View.GONE
            }

            // 3. Clean Agent Text with Live TTS Word Highlighting
            if (isSpeaking && cleanText.isNotBlank() && speakingStart in 0..cleanText.length && speakingEnd in (speakingStart + 1)..cleanText.length) {
                val spannable = SpannableStringBuilder(cleanText)
                // Spoken words: pure crisp white
                if (speakingStart > 0) {
                    spannable.setSpan(
                        ForegroundColorSpan(Color.parseColor("#FFFFFF")),
                        0,
                        speakingStart,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                // Currently spoken word: bright cyan, bold, soft highlight
                spannable.setSpan(
                    ForegroundColorSpan(Color.parseColor("#38BDF8")),
                    speakingStart,
                    speakingEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    speakingStart,
                    speakingEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    BackgroundColorSpan(Color.parseColor("#4438BDF8")),
                    speakingStart,
                    speakingEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                // Upcoming words: dimmed translucent white
                if (speakingEnd < cleanText.length) {
                    spannable.setSpan(
                        ForegroundColorSpan(Color.parseColor("#80FFFFFF")),
                        speakingEnd,
                        cleanText.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                tvAgentText.text = spannable
            } else {
                tvAgentText.text = cleanText
            }
        }

        private fun extractToolName(message: SessionMessage): String? {
            if (!message.toolCall.isNullOrBlank()) {
                val cleanCall = message.toolCall.substringBefore("(").substringBefore(" ")
                return cleanCall.ifBlank { message.toolCall }
            }
            if (message.text.contains("[Needle 2 Reflex]", ignoreCase = true)) return "Needle 2 Reflex"
            if (message.text.contains("[Pre-Routing Match]", ignoreCase = true)) return "Pre-Routing Engine"
            if (message.text.contains("[Groq", ignoreCase = true)) return "Groq LLaMA 3.3 70B"
            if (message.text.contains("[AGY", ignoreCase = true)) return "AGY Autonomous Agent"

            // Look inside steps for explicit tool execution, ignoring Trace
            for (step in message.steps) {
                if (step.startsWith("Trace:", ignoreCase = true) || step.contains("<think>")) continue
                if (step.contains("tool", ignoreCase = true) || step.contains("action", ignoreCase = true)) {
                    return step.replace("•", "").replace("Tool:", "").trim()
                }
            }

            // Look for tool tag in trace
            val traceStep = message.steps.firstOrNull { it.contains("Trace:", ignoreCase = true) }
            if (traceStep != null) {
                val match = Regex("\\[([a-zA-Z0-9_]+)\\]").find(traceStep)
                if (match != null && match.groupValues[1] != "think") {
                    return match.groupValues[1]
                }
            }
            return null
        }

        private fun extractThinking(message: SessionMessage): Pair<String?, String> {
            val fullText = message.text
            val match = THINK_REGEX.find(fullText)
            if (match != null) {
                val thought = match.groupValues[1].trim()
                val clean = fullText.replace(THINK_REGEX, "").replace("Thinking:", "").trim()
                return Pair(thought, clean)
            }
            
            // Check in steps for thinking trace
            val traceStep = message.steps.firstOrNull { it.contains("Trace:", ignoreCase = true) || it.contains("<think>", ignoreCase = true) }
            if (traceStep != null) {
                val matchInStep = THINK_REGEX.find(traceStep)
                val thought = matchInStep?.groupValues?.get(1)?.trim()
                    ?: traceStep.replace("Trace:", "").replace("Thinking:", "").replace("<think>", "").replace("</think>", "").trim()
                val clean = fullText.replace(THINK_REGEX, "").replace("Thinking:", "").trim()
                return Pair(thought, clean)
            }

            return Pair(null, fullText.trim())
        }

        private fun calculateThoughtDuration(thought: String): String {
            val latencyMatch = LATENCY_REGEX.find(thought)
            if (latencyMatch != null) {
                val ms = latencyMatch.groupValues[1].toLongOrNull() ?: 1500L
                val sec = String.format(java.util.Locale.US, "%.1f", ms / 1000.0)
                return "Thought for ${sec}s"
            }
            // Estimate based on thought length if no exact latency tag
            val wordCount = thought.split("\\s+".toRegex()).size
            val estimatedSec = Math.max(1, (wordCount / 15))
            return "Thought for ${estimatedSec} seconds"
        }
    }
}
