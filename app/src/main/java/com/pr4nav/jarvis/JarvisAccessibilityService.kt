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

        private val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")

        /** Coordinate tap: attempts Accessibility gesture, falls back to root input tap */
        fun gestureTap(x: Float, y: Float): Boolean {
            val s = instance
            if (s != null && Build.VERSION.SDK_INT >= 24) {
                val stroke = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(android.graphics.Path().apply {
                        moveTo(x, y); lineTo(x + 0.1f, y + 0.1f)
                    }, 0, 60))
                    .build()
                val ok = s.dispatchGesture(stroke, null, null)
                if (ok) return true
            }
            // Root fallback guarantees tap works anywhere
            val rootRes = Shell.root("input tap ${x.toInt()} ${y.toInt()}")
            return rootRes.rc == 0
        }

        fun screenText(): String = screenTextWithCoordinates()

        fun screenTextWithCoordinates(): String {
            val nodes = walk(250)
            if (nodes.isNotEmpty()) {
                val sb = StringBuilder()
                sb.append("=== SCREEN TEXT & COORDINATES (ASAP FORMAT) ===\n")
                for (n in nodes) {
                    val label = listOf(n.text, n.desc).firstOrNull { it.isNotBlank() }?.trim() ?: ""
                    if (label.isBlank() && !n.clickable && !n.editable && !n.scrollable) continue

                    val match = BOUNDS_REGEX.matchEntire(n.bounds)
                    val centerStr = if (match != null) {
                        val (l, t, r, b) = match.destructured
                        val cx = (l.toInt() + r.toInt()) / 2
                        val cy = (t.toInt() + b.toInt()) / 2
                        " center=($cx, $cy)"
                    } else ""

                    val tag = when {
                        n.editable -> "[INPUT]"
                        n.clickable -> "[BUTTON]"
                        n.scrollable -> "[SCROLL]"
                        else -> "[TEXT]"
                    }
                    val idStr = if (n.viewId.isNotBlank()) " id=${n.viewId.substringAfterLast('/')}" else ""
                    val content = if (label.isNotBlank()) "\"$label\"" else n.cls.substringAfterLast('.')
                    sb.append("$tag $content$centerStr bounds=${n.bounds}$idStr\n")
                }
                val result = sb.toString().trim()
                saveScreenCaptureTxt(result)
                return result
            }

            // Fallback via uiautomator dump when Accessibility is off or empty
            return dumpViaUiAutomator()
        }

        private fun dumpViaUiAutomator(): String {
            try {
                Shell.root("uiautomator dump /data/local/tmp/window_dump.xml")
                val dumpRes = Shell.root("cat /data/local/tmp/window_dump.xml")
                if (dumpRes.out.isNotBlank()) {
                    val nodeRegex = Regex("""<node[^>]*text="([^"]*)"[^>]*content-desc="([^"]*)"[^>]*clickable="([^"]*)"[^>]*bounds="([^"]*)"[^>]*>""")
                    val sb = StringBuilder()
                    sb.append("=== SCREEN TEXT & COORDINATES (UIAUTOMATOR DUMP) ===\n")
                    for (m in nodeRegex.findAll(dumpRes.out)) {
                        val (t, desc, clickable, bounds) = m.destructured
                        val label = listOf(t, desc).firstOrNull { it.isNotBlank() } ?: continue
                        val match = BOUNDS_REGEX.matchEntire(bounds)
                        val centerStr = if (match != null) {
                            val (l, top, r, b) = match.destructured
                            val cx = (l.toInt() + r.toInt()) / 2
                            val cy = (top.toInt() + b.toInt()) / 2
                            " center=($cx, $cy)"
                        } else ""
                        val tag = if (clickable == "true") "[BUTTON]" else "[TEXT]"
                        sb.append("$tag \"$label\"$centerStr bounds=$bounds\n")
                    }
                    val out = sb.toString().trim()
                    if (out.length > 50) {
                        saveScreenCaptureTxt(out)
                        return out
                    }
                }
            } catch (_: Exception) {}
            return "No readable elements detected on screen."
        }

        private fun saveScreenCaptureTxt(content: String) {
            try {
                val dir = java.io.File("/storage/emulated/0/JARVIS/workspace")
                if (!dir.exists()) dir.mkdirs()
                java.io.File(dir, "screen_capture.txt").writeText(content)
            } catch (_: Exception) {}
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (event != null) {
            val pkg = event.packageName?.toString()
            com.pr4nav.jarvis.companion.CompanionManager.onAccessibilityEvent(this, pkg, event.eventType)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
