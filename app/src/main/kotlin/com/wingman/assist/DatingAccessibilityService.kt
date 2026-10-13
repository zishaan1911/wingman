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
 * Bridge between the dating apps and the brains.
 *
 * New-match (0 messages)      → OpenerEngine (vision reads their profile) → schedule opener.
 * Our turn in a thread        → MatchStrategist (tone/pace/interest/readiness/facts)
 *                               → MatchMemory (callbacks) → DecayDetector (cooling?)
 *                               → EscalationPolicy → ReplyEngine → TimingEngine
 *                               → LearningStore bandit (nudge tone) → autonomy gate → send.
 * Their reply arrives          → LearningStore records the outcome (credits the tone used).
 * Swiping                      → swipeOnce() driven by an external pacer.
 * Profile audit                → requestPhotoAudit() (triggered from the UI).
 */
class DatingAccessibilityService : AccessibilityService() {

    private val store = ConversationStore()
    private val work = Executors.newSingleThreadExecutor()

    private lateinit var settings: Settings
    private lateinit var learning: LearningStore
    private lateinit var memory: MatchMemory
    private val decay = DecayDetector()

    private val groq by lazy { GroqClient(BuildConfigKey.GROQ_API_KEY) }
    private val replyEngine by lazy { ReplyEngine(groq) }
    private val openerEngine by lazy { OpenerEngine(groq) }
    private val swipeEngine by lazy { SwipeEngine(groq) }
    private val strategist by lazy { MatchStrategist(groq) }
    private val photoAuditor by lazy { PhotoAuditor(groq) }
    private val timing = TimingEngine()
    private lateinit var escalation: EscalationPolicy
    private lateinit var scheduler: SendScheduler

    private val scheduled = HashSet<String>()
    private val seenConvos = HashSet<String>()
    /** convoId -> (tone used, was it an opener) — to credit the outcome when they reply. */
    private val awaitingReply = HashMap<String, Pair<Tone, Boolean>>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        settings = Settings(this)
        learning = LearningStore(this)
        memory = MatchMemory(this)
        escalation = EscalationPolicy(settings)
        scheduler = SendScheduler { reply -> performScheduledSend(reply) }
        OverlayService.start(this)
        Log.i(TAG, "Wingman accessibility service connected")
    }

    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        val profile = AppProfile.forPackage(pkg) ?: return
        if (OverlayService.killSwitchEngaged) return
        val root = rootInActiveWindow ?: return
        handleChatScreen(root, profile)
    }

    // ---------------------------------------------------------------------------
    // CHAT PIPELINE
    // ---------------------------------------------------------------------------

    private fun handleChatScreen(root: AccessibilityNodeInfo, profile: AppProfile) {
        if (findInputBox(root, profile) == null) return
        if (!settings.autoReplyEnabled) return

        val matchName = profile.matchNameId?.let { findById(root, it)?.text?.toString() } ?: "match"
        val convoId = "${profile.app}:$matchName"
        val messages = parseConversation(root, profile)

        // Count a new match the first time we see its thread.
        if (convoId !in seenConvos) { seenConvos.add(convoId); learning.noteMatch() }

        val convo = store.ingest(convoId, profile.app, matchName, messages)

        // Credit an outcome: they replied to something we sent.
        if (convo.isOurTurn) awaitingReply.remove(convoId)?.let { (tone, wasOpener) ->
            learning.record(OutcomeKind.GOT_REPLY, tone)
            if (wasOpener) learning.recordOpenerReply()
        }

        if (convoId in scheduled) return

        // New match, no messages yet → send a personalized opener.
        if (convo.depth == 0) {
            if (settings.autoOpenerEnabled) { scheduled.add(convoId); scheduleOpener(convoId, matchName, profile) }
            return
        }

        if (!convo.isOurTurn) return
        scheduled.add(convoId)
        work.submit { runReplyPipeline(convo, convoId, matchName) }
    }

    private fun runReplyPipeline(convo: Conversation, convoId: String, matchName: String) {
        try {
            val persona = settings.persona()

            // 1. read this match (tone/pace/interest/readiness/facts)
            val read = strategist.analyze(convo, persona)
            val strategy = read.copy(
                tone = settings.forceTone ?: read.tone,   // dev-panel overrides win
                pace = settings.forcePace ?: read.pace,
            )
            convo.strategy = strategy

            // 2. remember new facts for future callbacks
            memory.merge(convoId, strategy.facts)

            // 3. cooling off? optionally disengage
            val state = decay.assess(convo, strategy)
            if (state == DecayState.COLD && settings.disengageWhenCold) {
                OverlayService.showSwipe(SwipeDecision(false, 1.0, "$matchName: cold, disengaged"))
                scheduled.remove(convoId); return
            }

            // 4. learning loop may nudge the tone toward what's earning you replies
            val tone = if (settings.banditEnabled) learning.chooseTone(strategy.tone) else strategy.tone
            val effStrategy = strategy.copy(tone = tone)

            // 5. big move?
            val move = escalation.decide(convo, effStrategy)

            // 6. draft, with memory callbacks woven in
            val text = replyEngine.draft(convo, persona, effStrategy, move, memory.facts(convoId))

            // 7. when
            val sendAt = timing.computeSendTime(convo, persona, effStrategy.pace)

            // 8. autonomy gate
            val needsApproval = when (settings.autonomy) {
                AutonomyMode.SUPERVISED -> true
                AutonomyMode.APPROVE_BIG_MOVES -> escalation.isBigMove(move)
                AutonomyMode.FULL_AUTO -> false
            }
            val reply = ScheduledReply(convoId, matchName, text, sendAt, move, needsApproval, tone, isOpener = false)
            dispatch(reply)
        } catch (e: Exception) {
            Log.e(TAG, "reply pipeline failed", e); scheduled.remove(convoId)
        }
    }

    private fun scheduleOpener(convoId: String, matchName: String, profile: AppProfile) {
        captureScreenJpeg { shot ->
            work.submit {
                try {
                    val persona = settings.persona()
                    val text = if (shot != null) openerEngine.fromProfile(shot, matchName, persona)
                    else openerEngine.fromNameOnly(matchName, persona)
                    // No history yet: a prompt-but-not-instant opener, shifted into active hours.
                    val convo = store.get(convoId) ?: Conversation(convoId, profile.app, matchName)
                    val sendAt = timing.computeSendTime(convo, persona, Pace.WARM_PROMPT)
                    val needsApproval = settings.autonomy == AutonomyMode.SUPERVISED
                    val reply = ScheduledReply(convoId, matchName, text, sendAt, EscalationAction.NONE, needsApproval, Tone.GENUINE, isOpener = true)
                    dispatch(reply)
                } catch (e: Exception) { Log.e(TAG, "opener failed", e); scheduled.remove(convoId) }
            }
        }
    }

    /** Either schedule directly, or route through the overlay for your approval. */
    private fun dispatch(reply: ScheduledReply) {
        if (reply.needsApproval) {
            OverlayService.requestApproval(
                reply,
                onApprove = { scheduler.schedule(it); OverlayService.showPending(it.matchName, it.text, it.sendAt) },
                onReject = { scheduled.remove(it.conversationId) },
            )
        } else {
            scheduler.schedule(reply)
            OverlayService.showPending(reply.matchName, reply.text, reply.sendAt)
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
        if (OverlayService.killSwitchEngaged || !settings.autoReplyEnabled) { scheduled.remove(reply.conversationId); return }
        val root = rootInActiveWindow
        val profile = root?.packageName?.toString()?.let { AppProfile.forPackage(it) }
        if (root == null || profile == null) {
            Log.w(TAG, "send skipped: target app not in foreground"); scheduled.remove(reply.conversationId); return
        }
        val input = findInputBox(root, profile) ?: run { scheduled.remove(reply.conversationId); return }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply.text)
        }
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val sendBtn = profile.sendButtonId?.let { findById(root, it) }
        if (sendBtn != null) sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        else Log.w(TAG, "no send button id; set sendButtonId in AppProfile")

        // record outcomes for the learning loop
        if (reply.isOpener) learning.record(OutcomeKind.OPENER_SENT, reply.tone)
        learning.record(OutcomeKind.REPLY_SENT, reply.tone)
        when (reply.escalation) {
            EscalationAction.PROPOSE_DATE -> learning.record(OutcomeKind.PROPOSED_DATE, reply.tone)
            EscalationAction.MOVE_OFF_APP -> learning.record(OutcomeKind.MOVED_OFF_APP, reply.tone)
            EscalationAction.NONE -> {}
        }
        awaitingReply[reply.conversationId] = reply.tone to reply.isOpener

        OverlayService.clearPending()
        scheduled.remove(reply.conversationId)
        Log.i(TAG, "sent to ${reply.conversationId} tone=${reply.tone} [${reply.escalation}]")
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

    /** Audit the profile photos currently on screen (triggered from the UI). */
    fun requestPhotoAudit(onResult: (PhotoAuditor.Audit) -> Unit) {
        captureScreenJpeg { shot ->
            if (shot == null) { onResult(PhotoAuditor.Audit(emptyList(), emptyList(), "no screenshot")); return@captureScreenJpeg }
            work.submit {
                val a = runCatching { photoAuditor.audit(listOf(shot), settings.swipeCriteria) }
                    .getOrElse { PhotoAuditor.Audit(emptyList(), emptyList(), "audit failed: ${it.message}") }
                onResult(a)
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

    fun dumpNodeTree() {
        val root = rootInActiveWindow ?: return
        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            n ?: return
            Log.d(TAG, "${"  ".repeat(depth)}${n.className} id=${n.viewIdResourceName} text=${n.text} editable=${n.isEditable}")
            for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
        }
        visit(root, 0)
    }

    companion object {
        private const val TAG = "Wingman"
        /** So the UI can trigger a photo audit / check whether the service is running. */
        @Volatile var instance: DatingAccessibilityService? = null
            private set
    }
}

/** build.gradle.kts injects GROQ_API_KEY (from local.properties) into BuildConfig. */
object BuildConfigKey {
    val GROQ_API_KEY: String get() = BuildConfig.GROQ_API_KEY
}
