package com.wingman.assist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.Executors

/**
 * The bridge between the dating apps and the brains (engines).
 *
 * Per incoming message, on a chat screen:
 *   parse → ingest → MatchStrategist (per-match tone/pace/interest/readiness)
 *   → EscalationPolicy (ask out? move off-app?) → ReplyEngine (draft)
 *   → TimingEngine (when) → autonomy gate (auto-send vs approve) → schedule/send.
 *
 * Swiping is driven via swipeOnce() by an external pacer loop.
 */
class DatingAccessibilityService : AccessibilityService() {

    private val store = ConversationStore()
    private val work = Executors.newSingleThreadExecutor()

    private lateinit var settings: Settings
    private val groq by lazy { GroqClient(BuildConfigKey.GROQ_API_KEY) }
    private val replyEngine by lazy { ReplyEngine(groq) }
    private val swipeEngine by lazy { SwipeEngine(groq) }
    private val strategist by lazy { MatchStrategist(groq) }
    private val timing = TimingEngine()
    private lateinit var escalation: EscalationPolicy
    private lateinit var scheduler: SendScheduler

    private val scheduled = HashSet<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = Settings(this)
        escalation = EscalationPolicy(settings)
        scheduler = SendScheduler { reply -> performScheduledSend(reply) }
        OverlayService.start(this)
        Log.i(TAG, "Wingman accessibility service connected")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        val profile = AppProfile.forPackage(pkg) ?: return
        if (OverlayService.killSwitchEngaged) return
        val root = rootInActiveWindow ?: return
        handleChatScreen(root, profile)
    }

    // ---------------------------------------------------------------------------
    // CHAT READING + ADAPTIVE REPLY PIPELINE
    // ---------------------------------------------------------------------------

    private fun handleChatScreen(root: AccessibilityNodeInfo, profile: AppProfile) {
        if (findInputBox(root, profile) == null) return   // not a chat screen
        if (!settings.autoReplyEnabled) return

        val matchName = profile.matchNameId
            ?.let { findById(root, it)?.text?.toString() } ?: "match"
        val messages = parseConversation(root, profile)
        if (messages.isEmpty()) return

        val convoId = "${profile.app}:$matchName"
        val convo = store.ingest(convoId, profile.app, matchName, messages)
        if (!convo.isOurTurn || convoId in scheduled) return

        scheduled.add(convoId)
        work.submit {
            try {
                // 1. read this match
                val strategy = strategist.analyze(convo, settings.persona()).let { s ->
                    // dev-panel overrides win if set
                    s.copy(
                        tone = settings.forceTone ?: s.tone,
                        pace = settings.forcePace ?: s.pace,
                    )
                }
                convo.strategy = strategy

                // 2. decide any big move
                val move = escalation.decide(convo, strategy)

                // 3. draft in the right tone, with the move woven in
                val text = replyEngine.draft(convo, settings.persona(), strategy, move)

                // 4. when to send (per-match pace)
                val sendAt = timing.computeSendTime(convo, settings.persona(), strategy.pace)

                // 5. autonomy gate
                val needsApproval = when (settings.autonomy) {
                    AutonomyMode.SUPERVISED -> true
                    AutonomyMode.APPROVE_BIG_MOVES -> escalation.isBigMove(move)
                    AutonomyMode.FULL_AUTO -> false
                }
                val reply = ScheduledReply(convoId, matchName, text, sendAt, move, needsApproval)

                if (needsApproval) {
                    OverlayService.requestApproval(
                        reply,
                        onApprove = { scheduler.schedule(it); OverlayService.showPending(it.matchName, it.text, it.sendAt) },
                        onReject = { scheduled.remove(it.conversationId) },
                    )
                } else {
                    scheduler.schedule(reply)
                    OverlayService.showPending(matchName, text, sendAt)
                }
            } catch (e: Exception) {
                Log.e(TAG, "reply pipeline failed", e)
                scheduled.remove(convoId)
            }
        }
    }

    private fun parseConversation(root: AccessibilityNodeInfo, profile: AppProfile): List<Message> {
        val out = ArrayList<Message>()
        val screenWidth = Rect().also { root.getBoundsInScreen(it) }.width().coerceAtLeast(1)

        fun visit(node: AccessibilityNodeInfo?) {
            node ?: return
            val id = node.viewIdResourceName
            val text = node.text?.toString()?.trim()
            val isRow = profile.messageRowId != null && id == profile.messageRowId
            val isIncoming = profile.incomingTextId != null && id == profile.incomingTextId
            val isOutgoing = profile.outgoingTextId != null && id == profile.outgoingTextId
            if (!text.isNullOrEmpty() && (isRow || isIncoming || isOutgoing)) {
                val sender = when {
                    isIncoming -> Sender.THEM
                    isOutgoing -> Sender.ME
                    else -> {
                        val r = Rect().also { node.getBoundsInScreen(it) }
                        if (r.centerX() < screenWidth / 2) Sender.THEM else Sender.ME
                    }
                }
                out.add(Message(sender, text, Instant.now()))
            }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(root)
        return out
    }

    // ---------------------------------------------------------------------------
    // SENDING
    // ---------------------------------------------------------------------------

    private fun performScheduledSend(reply: ScheduledReply) {
        if (OverlayService.killSwitchEngaged || !settings.autoReplyEnabled) {
            scheduled.remove(reply.conversationId); return
        }
        val root = rootInActiveWindow
        val profile = root?.packageName?.toString()?.let { AppProfile.forPackage(it) }
        if (root == null || profile == null) {
            Log.w(TAG, "send skipped: target app not in foreground")
            scheduled.remove(reply.conversationId); return
        }
        val input = findInputBox(root, profile) ?: run { scheduled.remove(reply.conversationId); return }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply.text)
        }
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        val sendBtn = profile.sendButtonId?.let { findById(root, it) }
        if (sendBtn != null) sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        else Log.w(TAG, "no send button id; set sendButtonId in AppProfile")

        OverlayService.clearPending()
        scheduled.remove(reply.conversationId)
        Log.i(TAG, "sent to ${reply.conversationId} [${reply.escalation}]")
    }

    // ---------------------------------------------------------------------------
    // SWIPING
    // ---------------------------------------------------------------------------

    fun swipeOnce(onDone: (SwipeDecision?) -> Unit) {
        if (OverlayService.killSwitchEngaged || !settings.autoSwipeEnabled) { onDone(null); return }
        captureScreenJpeg { shot ->
            if (shot == null) { onDone(null); return@captureScreenJpeg }
            work.submit {
                try {
                    val decision = swipeEngine.evaluate(listOf(shot), settings.swipePrefs())
                    if (decision.confidence >= 0.35) { if (decision.like) swipeRight() else swipeLeft() }
                    OverlayService.showSwipe(decision)
                    onDone(decision)
                } catch (e: Exception) { Log.e(TAG, "swipe eval failed", e); onDone(null) }
            }
        }
    }

    private fun swipeRight() = fling(fromLeft = true)
    private fun swipeLeft() = fling(fromLeft = false)

    private fun fling(fromLeft: Boolean) {
        val m = resources.displayMetrics
        val w = m.widthPixels; val h = m.heightPixels
        val startX = w * 0.5f; val startY = h * 0.5f
        val endX = if (fromLeft) w * 0.9f else w * 0.1f
        val endY = startY - h * 0.08f
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 180)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun captureScreenJpeg(cb: (ByteArray?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { cb(null); return }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, work, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val buffer = result.hardwareBuffer
                    val bmp = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    buffer.close()
                    if (bmp == null) { cb(null); return }
                    val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                    val bos = ByteArrayOutputStream()
                    sw.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                    cb(bos.toByteArray())
                } catch (e: Exception) { Log.e(TAG, "screenshot convert failed", e); cb(null) }
            }
            override fun onFailure(errorCode: Int) { Log.w(TAG, "screenshot failed $errorCode"); cb(null) }
        })
    }

    // ---------------------------------------------------------------------------
    // NODE HELPERS
    // ---------------------------------------------------------------------------

    private fun findById(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()

    private fun findInputBox(root: AccessibilityNodeInfo, profile: AppProfile): AccessibilityNodeInfo? {
        profile.inputBoxId?.let { id -> findById(root, id)?.let { return it } }
        var found: AccessibilityNodeInfo? = null
        fun visit(n: AccessibilityNodeInfo?) {
            n ?: return
            if (found != null) return
            if (n.isEditable) { found = n; return }
            for (i in 0 until n.childCount) visit(n.getChild(i))
        }
        visit(root)
        return found
    }

    /** DEBUG: dump the live node tree to Logcat to discover resource IDs. */
    fun dumpNodeTree() {
        val root = rootInActiveWindow ?: return
        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            n ?: return
            Log.d(TAG, "${"  ".repeat(depth)}${n.className} id=${n.viewIdResourceName} text=${n.text} editable=${n.isEditable}")
            for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
        }
        visit(root, 0)
    }

    companion object { private const val TAG = "Wingman" }
}

/** build.gradle.kts injects GROQ_API_KEY (from local.properties) into BuildConfig. */
object BuildConfigKey {
    val GROQ_API_KEY: String get() = BuildConfig.GROQ_API_KEY
}
