package com.wingman.assist

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.time.Instant

/**
 * Heuristic, ID-free detection of the chat UI, so the app works WITHOUT hand-filled
 * resource IDs in AppProfiles.kt. It reads the live accessibility tree and infers:
 *   • the message input box   (first editable field)
 *   • the send button         (a clickable "send" control, or the clickable to the right of input)
 *   • the match name          (the top-bar title text)
 *   • the conversation        (text bubbles between header and input, left = them / right = you)
 *
 * Best-effort: it won't be perfect on every app version, but it removes the mandatory manual
 * setup. Discovered resource IDs are cached by the caller (SelectorStore) so detection
 * stabilises after the first screen.
 */
object AutoUi {

    private val SEND = Regex("(?i)\\bsend\\b")

    fun firstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { if (found == null && it.isEditable) found = it }
        return found
    }

    /** A clickable "send" control: prefer one labelled send, else the clickable just right of the input. */
    fun sendButton(root: AccessibilityNodeInfo, input: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val inB = input?.let { Rect().also { r -> it.getBoundsInScreen(r) } }
        var byLabel: AccessibilityNodeInfo? = null
        var byPos: AccessibilityNodeInfo? = null
        var bestRight = Int.MIN_VALUE
        walk(root) { n ->
            if (!n.isClickable) return@walk
            val label = "${n.contentDescription ?: ""} ${n.text ?: ""}"
            if (byLabel == null && SEND.containsMatchIn(label)) byLabel = n
            if (inB != null) {
                val b = Rect().also { n.getBoundsInScreen(it) }
                val sameRow = b.centerY() in (inB.top - 24)..(inB.bottom + 24)
                if (sameRow && b.left >= inB.right - 16 && b.centerX() > bestRight) { bestRight = b.centerX(); byPos = n }
            }
        }
        return byLabel ?: byPos
    }

    /** The top-bar title: the highest short, non-editable text node in the header band. */
    fun matchNameNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val headerMaxY = screen.top + (screen.height() * 0.18).toInt()
        var best: AccessibilityNodeInfo? = null
        var bestTop = Int.MAX_VALUE
        walk(root) { n ->
            val t = n.text?.toString()?.trim() ?: return@walk
            if (t.isEmpty() || t.length > 30 || n.isEditable) return@walk
            if (n.className?.contains("TextView") != true) return@walk
            val b = Rect().also { n.getBoundsInScreen(it) }
            if (b.top <= headerMaxY && b.top < bestTop) { bestTop = b.top; best = n }
        }
        return best
    }

    /** Parse the visible conversation: text bubbles between the header and the input, by side. */
    fun messages(root: AccessibilityNodeInfo, input: AccessibilityNodeInfo?): List<Message> {
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val mid = screen.centerX()
        val headerBottom = screen.top + (screen.height() * 0.12).toInt()
        val inputTop = input?.let { Rect().also { r -> it.getBoundsInScreen(r) }.top }
            ?: (screen.bottom - (screen.height() * 0.1).toInt())

        val collected = ArrayList<Pair<Int, Message>>()
        walk(root) { n ->
            val t = n.text?.toString()?.trim() ?: return@walk
            if (t.isEmpty() || n.isEditable) return@walk
            if (n.className?.contains("TextView") != true) return@walk
            val b = Rect().also { n.getBoundsInScreen(it) }
            if (b.top < headerBottom || b.bottom > inputTop) return@walk   // skip header + input zones
            val sender = if (b.centerX() < mid) Sender.THEM else Sender.ME
            collected.add(b.top to Message(sender, t, Instant.now()))
        }
        return collected.sortedBy { it.first }.map { it.second }   // top-to-bottom = chronological
    }

    private fun walk(node: AccessibilityNodeInfo?, action: (AccessibilityNodeInfo) -> Unit) {
        node ?: return
        action(node)
        for (i in 0 until node.childCount) walk(node.getChild(i), action)
    }
}
