package com.pr4nav.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
            private set

        data class NodeRef(
            val path: List<Int>, val text: String, val desc: String,
            val cls: String, val viewId: String,
            val clickable: Boolean, val scrollable: Boolean,
            val editable: Boolean, val checked: Boolean?,
            val bounds: String
        )

        fun walk(maxNodes: Int = 120): List<NodeRef> {
            val root = instance?.rootInActiveWindow ?: return emptyList()
            val out = ArrayList<NodeRef>()
            fun visit(n: AccessibilityNodeInfo?, path: List<Int>) {
                if (n == null || out.size >= maxNodes) return
                val r = Rect(); n.getBoundsInScreen(r)
                out.add(
                    NodeRef(
                        path, n.text?.toString() ?: "", n.contentDescription?.toString() ?: "",
                        n.className?.toString() ?: "", n.viewIdResourceName ?: "",
                        n.isClickable, n.isScrollable, n.isEditable,
                        if (n.isChecked) true else if (n.isCheckable) false else null,
                        r.toShortString()
                    )
                )
                for (i in 0 until n.childCount) visit(n.getChild(i), path + i)
            }
            visit(root, emptyList())
            return out
        }

        fun nodeAt(path: List<Int>): AccessibilityNodeInfo? {
            var n = instance?.rootInActiveWindow ?: return null
            for (i in path) {
                n = if (i < n.childCount) n.getChild(i) ?: return null else return null
            }
            return n
        }

        private fun find(pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            val root = instance?.rootInActiveWindow ?: return null
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                if (pred(n)) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
            return null
        }

        fun findByText(text: String): AccessibilityNodeInfo? =
            find { it.text?.toString()?.contains(text, ignoreCase = true) == true }

        fun findByDesc(desc: String): AccessibilityNodeInfo? =
            find { it.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true }

        fun findById(viewId: String): AccessibilityNodeInfo? =
            instance?.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()

        fun clickByText(text: String): Boolean {
            val n = findByText(text) ?: findByDesc(text) ?: return false
            var target = n
            var hops = 0
            while (!target.isClickable && target.parent != null && hops < 3) {
                target = target.parent; hops++
            }
            return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        fun clickAt(path: List<Int>): Boolean =
            nodeAt(path)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

        fun longClick(path: List<Int>): Boolean =
            nodeAt(path)?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true

        fun scroll(forward: Boolean): Boolean {
            val n = find { if (forward) it.isScrollable else it.isScrollable }
                ?: return false
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            return n.performAction(action)
        }

        fun typeText(text: String): Boolean {
            val editable = find { it.isEditable }
                ?: return false
            val args = android.os.Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        fun global(actionName: String): Boolean {
            val s = instance ?: return false
            val action = when (actionName.lowercase()) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
                else -> return false
            }
            return s.performGlobalAction(action)
        }

        /** Coordinate fallback — only when no semantic action fits. */
        fun gestureTap(x: Float, y: Float): Boolean {
            val s = instance ?: return false
            if (Build.VERSION.SDK_INT < 24) return false
            val stroke = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(android.graphics.Path().apply {
                    moveTo(x, y); lineTo(x + 0.1f, y + 0.1f)
                }, 0, 60))
                .build()
            return s.dispatchGesture(stroke, null, null)
        }

        fun screenText(): String {
            val nodes = walk(200)
            val sb = StringBuilder()
            var lastDepth = -1
            for (n in nodes) {
                val depth = n.path.size
                val indent = "  ".repeat(depth.coerceAtMost(8))
                val label = listOf(n.text, n.desc).firstOrNull { it.isNotBlank() } ?: ""
                if (label.isBlank() && !n.clickable && !n.editable && !n.scrollable) continue
                if (depth <= lastDepth && sb.isNotEmpty()) sb.append('\n')
                sb.append(indent)
                if (n.clickable) sb.append("[CLICK] ")
                if (n.editable) sb.append("[INPUT] ")
                if (n.scrollable) sb.append("[SCROLL] ")
                sb.append(label.ifBlank { n.cls.substringAfterLast('.') })
                if (n.bounds.isNotBlank()) sb.append(" @${n.bounds}")
                lastDepth = depth
            }
            return sb.toString()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
