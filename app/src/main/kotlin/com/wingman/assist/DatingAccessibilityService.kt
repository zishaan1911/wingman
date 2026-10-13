package com.wingman.assist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
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
import java.util.concurrent.TimeUnit

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

    private lateinit var store: ConversationStore
    private val work = Executors.newSingleThreadExecutor()
    /** Separate pool for screenshot callbacks so [captureJpegBlocking] can block `work` safely. */
    private val shotExec = Executors.newCachedThreadPool()

    private lateinit var settings: Settings
    private lateinit var learning: LearningStore
    private lateinit var memory: MatchMemory
    private lateinit var notifier: Notifier
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
    private lateinit var swipeLoop: SwipeLoopController

    private val scheduled = HashSet<String>()
    private val seenConvos = HashSet<String>()
    /** convoId -> (tone used, was it an opener) — to credit the outcome when they reply. */
    private val awaitingReply = HashMap<String, Pair<Tone, Boolean>>()
    /** Replies whose time has come but whose chat isn't on screen yet; sent when it is. */
    private val dueSends = java.util.concurrent.ConcurrentHashMap<String, ScheduledReply>()
    @Volatile private var lastOpenAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        settings = Settings(this)
        store = ConversationStore(this)
        learning = LearningStore(this)
        memory = MatchMemory(this)
        notifier = Notifier(this)
        escalation = EscalationPolicy(settings)
        scheduler = SendScheduler(this) { reply -> performScheduledSend(reply) }
        // Re-arm any sends that were pending when we were last killed.
        scheduler.loadPending().forEach { scheduled.add(it.conversationId) }
        swipeLoop = SwipeLoopController(
            pacer = SwipePacer(dailyCap = settings.dailySwipeCap),
            isEnabled = { settings.autoSwipeEnabled && !OverlayService.killSwitchEngaged },
            canSwipeNow = { isOnSwipeScreen() },
            doSwipe = { done -> swipeOnce { done() } },
            onNotReady = { if (settings.autoOpenForSwipe) openApp(settings.swipeApp) },
        )
        swipeLoop.start()
        OverlayService.start(this)
        Log.i(TAG, "Wingman accessibility service connected")
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        if (::swipeLoop.isInitialized) swipeLoop.shutdown()
        if (::store.isInitialized) store.flush()
        work.shutdownNow(); shotExec.shutdownNow()
        instance = null
        super.onDestroy()
    }

    /**
     * Heuristic: we're on the swipe deck when a dating app is foregrounded and the screen
     * has NO chat input box (chat screens do; the discovery deck doesn't). Good enough to
     * avoid swiping on chat/match-list screens; refine with AppProfile deck ids if needed.
     */
    private fun isOnSwipeScreen(): Boolean {
        val root = rootInActiveWindow ?: return false
        val profile = root.packageName?.toString()?.let { AppProfile.forPackage(it) } ?: return false
        return findInputBox(root, profile) == null
    }

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

        // A send was waiting for this exact chat to be on screen — fire it now.
        dueSends.remove(convoId)?.let { pending -> work.submit { doSend(pending) }; return }

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

            // 2b. safety flag: possible under-18 → surface it (we never act sexual on these)
            if (strategy.possibleMinor && settings.notifyImportant) {
                notifier.alert("Possible under-18 match", "$matchName — forced clean. Worth reviewing.")
            }

            // 3. cooling off? optionally disengage
            val state = decay.assess(convo, strategy)
            if (state == DecayState.COLD && settings.disengageWhenCold) {
                OverlayService.showSwipe(SwipeDecision(false, 1.0, "$matchName: cold, disengaged"))
                scheduled.remove(convoId); return
            }

            // 4. learning loop may nudge the tone toward what's earning you replies
            val tone = if (settings.banditEnabled) learning.chooseTone(strategy.tone) else strategy.tone

            // 4b. flirtation: adult-only clamp (hard), then your dev-panel ceiling
            val effFlirt = when {
                strategy.possibleMinor -> Flirt.CLEAN                          // non-negotiable
                else -> minOf(strategy.flirt, settings.flirtCeiling)           // enums compare by ordinal
            }
            val effExplicit = strategy.explicit && !strategy.possibleMinor
            val effStrategy = strategy.copy(tone = tone, flirt = effFlirt, explicit = effExplicit)

            // 5. big move?
            val move = escalation.decide(convo, effStrategy)

            // 6. draft, with memory callbacks woven in
            val text = replyEngine.draft(convo, persona, effStrategy, move, memory.facts(convoId))

            // 7. when
            val sendAt = timing.computeSendTime(convo, persona, effStrategy.pace)

            // 8. autonomy gate — explicit ALWAYS routes to you, regardless of autonomy mode
            val needsApproval = effExplicit || when (settings.autonomy) {
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
        work.submit {
            try {
                val persona = settings.persona()
                val shots = captureProfile(settings.profilePanels)
                val text = if (shots.isNotEmpty()) openerEngine.fromProfile(shots, matchName, persona)
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

    /** Either schedule directly, or route through the overlay for your approval. */
    private fun dispatch(reply: ScheduledReply) {
        if (reply.needsApproval) {
            if (settings.notifyImportant) {
                val why = when (reply.escalation) {
                    EscalationAction.PROPOSE_DATE -> "date"
                    EscalationAction.MOVE_OFF_APP -> "off-app"
                    else -> "review"
                }
                notifier.approvalNeeded(reply.matchName, reply.text, why)
            }
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

    /**
     * Timer fired for a reply. If we're already on the correct chat, send now. Otherwise queue
     * it and (optionally) launch the app — it will send automatically once the RIGHT chat is on
     * screen. We never send into whatever chat happens to be open.
     */
    private fun performScheduledSend(reply: ScheduledReply) {
        if (OverlayService.killSwitchEngaged || !settings.autoReplyEnabled) { cleanupSend(reply.conversationId); return }
        val root = rootInActiveWindow
        val profile = root?.packageName?.toString()?.let { AppProfile.forPackage(it) }
        val onRightChat = root != null && profile != null && currentConvoId(root, profile) == reply.conversationId
        if (onRightChat) { work.submit { doSend(reply) }; return }

        // Not on the right chat → keep it pending; bring the app up; it'll fire from handleChatScreen.
        dueSends[reply.conversationId] = reply
        if (settings.autoOpenForSend) appOf(reply.conversationId)?.let { openApp(it) }
        if (settings.notifyImportant) notifier.readyToSend(reply.matchName, reply.text)
        Log.i(TAG, "send queued (waiting for ${reply.conversationId} to be on screen)")
    }

    /**
     * Perform the actual type + send. Runs on the `work` thread. Re-verifies we're on the
     * correct chat first (foreground can change between queueing and execution) — if not, it
     * re-queues rather than sending to the wrong person.
     */
    private fun doSend(reply: ScheduledReply) {
        if (OverlayService.killSwitchEngaged || !settings.autoReplyEnabled) { cleanupSend(reply.conversationId); return }
        val root = rootInActiveWindow
        val profile = root?.packageName?.toString()?.let { AppProfile.forPackage(it) }
        if (root == null || profile == null || currentConvoId(root, profile) != reply.conversationId) {
            dueSends[reply.conversationId] = reply; return    // not the right chat (any more) → keep pending
        }
        val input = findInputBox(root, profile) ?: run { dueSends[reply.conversationId] = reply; return }

        if (settings.humanTyping) typeHumanlike(profile, reply.text) else setTextOn(input, reply.text)
        val freshRoot = rootInActiveWindow ?: root
        val sendBtn = profile.sendButtonId?.let { findById(freshRoot, it) }
        if (sendBtn != null) sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        else Log.w(TAG, "no send button id; set sendButtonId in AppProfile")

        if (reply.isOpener) learning.record(OutcomeKind.OPENER_SENT, reply.tone)
        learning.record(OutcomeKind.REPLY_SENT, reply.tone)
        when (reply.escalation) {
            EscalationAction.PROPOSE_DATE -> learning.record(OutcomeKind.PROPOSED_DATE, reply.tone)
            EscalationAction.MOVE_OFF_APP -> learning.record(OutcomeKind.MOVED_OFF_APP, reply.tone)
            EscalationAction.NONE -> {}
        }
        awaitingReply[reply.conversationId] = reply.tone to reply.isOpener
        OverlayService.clearPending()
        cleanupSend(reply.conversationId)
        Log.i(TAG, "sent to ${reply.conversationId} tone=${reply.tone} [${reply.escalation}]")
    }

    private fun cleanupSend(id: String) { scheduled.remove(id); dueSends.remove(id) }

    // ---------------------------------------------------------------------------
    // SWIPING
    // ---------------------------------------------------------------------------

    fun swipeOnce(onDone: (SwipeDecision?) -> Unit) {
        if (OverlayService.killSwitchEngaged || !settings.autoSwipeEnabled) { onDone(null); return }
        work.submit {
            try {
                // Judge the VISIBLE card only — don't open/scroll into the profile while swiping.
                // (Deep personality read happens after matching.) Fast and more human.
                val shot = captureJpegBlocking() ?: run { onDone(null); return@submit }
                val decision = swipeEngine.evaluate(listOf(shot), settings.swipePrefs())
                if (decision.confidence >= 0.35) { if (decision.like) swipeRight() else swipeLeft() }
                OverlayService.showSwipe(decision)
                onDone(decision)
            } catch (e: Exception) { Log.e(TAG, "swipe eval failed", e); onDone(null) }
        }
    }

    /** Audit your profile photos (open your profile first). Captures multiple panels. */
    fun requestPhotoAudit(onResult: (PhotoAuditor.Audit) -> Unit) {
        work.submit {
            val shots = captureProfile(settings.profilePanels)
            val a = if (shots.isEmpty()) PhotoAuditor.Audit(emptyList(), emptyList(), "no screenshot")
            else runCatching { photoAuditor.audit(shots, settings.swipePrefs().summary()) }
                .getOrElse { PhotoAuditor.Audit(emptyList(), emptyList(), "audit failed: ${it.message}") }
            onResult(a)
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

    /**
     * Capture the screen as JPEG bytes IN MEMORY ONLY. Nothing is ever written to disk,
     * the gallery, or MediaStore — the bytes live in RAM just long enough to be analysed,
     * then are dropped. Bitmaps are recycled immediately so nothing lingers. (The bytes are
     * sent to the vision model over the network; that's inherent to cloud analysis.)
     */
    private fun captureScreenJpeg(cb: (ByteArray?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { cb(null); return }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, shotExec, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                var bmp: Bitmap? = null
                var sw: Bitmap? = null
                try {
                    val buffer = result.hardwareBuffer
                    bmp = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    buffer.close()
                    if (bmp == null) { cb(null); return }
                    sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                    val bos = ByteArrayOutputStream()
                    sw.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                    cb(bos.toByteArray())
                } catch (e: Exception) {
                    Log.e(TAG, "screenshot convert failed", e); cb(null)
                } finally {
                    sw?.recycle(); bmp?.recycle()   // wipe pixels from memory immediately
                }
            }
            override fun onFailure(errorCode: Int) { Log.w(TAG, "screenshot failed $errorCode"); cb(null) }
        })
    }

    /** Synchronous capture — call ONLY from the `work` thread (screenshot callback runs on shotExec). */
    private fun captureJpegBlocking(): ByteArray? {
        val latch = java.util.concurrent.CountDownLatch(1)
        val holder = arrayOfNulls<ByteArray>(1)
        captureScreenJpeg { holder[0] = it; latch.countDown() }
        return try { latch.await(6, TimeUnit.SECONDS); holder[0] } catch (e: Exception) { null }
    }

    /**
     * Capture several panels of the current profile — screenshot, scroll down, repeat —
     * so the vision model sees all photos + the full bio, not just the first screen.
     * Must run on the `work` thread.
     */
    private fun captureProfile(panels: Int): List<ByteArray> {
        val shots = ArrayList<ByteArray>()
        val n = panels.coerceIn(1, 6)
        for (i in 0 until n) {
            captureJpegBlocking()?.let { shots.add(it) }
            if (i < n - 1) { scrollProfileDown(); try { Thread.sleep(700) } catch (_: Exception) {} }
        }
        return shots
    }

    /** Swipe up in the middle of the screen to scroll the profile content down. */
    private fun scrollProfileDown() {
        val m = resources.displayMetrics
        val x = m.widthPixels * 0.5f
        val path = Path().apply { moveTo(x, m.heightPixels * 0.75f); lineTo(x, m.heightPixels * 0.30f) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 260)).build(), null, null)
    }

    // ---------------------------------------------------------------------------
    // NODE HELPERS
    // ---------------------------------------------------------------------------

    private fun setTextOn(node: AccessibilityNodeInfo, s: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Type the message one character at a time with human-ish delays, re-resolving the
     * input field each step (the tree changes as text grows). Instant paste is a classic
     * bot tell; this looks like real typing — slower per-character, with pauses after
     * spaces and punctuation, and the odd longer "thinking" pause. Runs on a background
     * thread (the scheduler), so the Thread.sleep calls are safe.
     */
    private fun typeHumanlike(profile: AppProfile, text: String) {
        for (i in 1..text.length) {
            val node = (rootInActiveWindow ?: break).let { findInputBox(it, profile) } ?: break
            setTextOn(node, text.substring(0, i))
            val ch = text[i - 1]
            var delay = (45..150).random().toLong()
            if (ch == ' ') delay += (0..110).random()
            if (ch in ".,!?") delay += (120..320).random()
            if (Math.random() < 0.06) delay += (300..900).random()   // occasional pause
            try { Thread.sleep(delay) } catch (_: Exception) { break }
        }
    }

    /** Launch a dating app to the foreground (throttled) so we can act on it. */
    private fun openApp(app: App) {
        val pkg = AppProfile.ALL.firstOrNull { it.app == app }?.packageName ?: return
        val now = System.currentTimeMillis()
        if (now - lastOpenAt < 15_000) return                  // don't thrash the launcher
        val intent = packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        try { startActivity(intent); lastOpenAt = now; Log.i(TAG, "opened $pkg") }
        catch (e: Exception) { Log.w(TAG, "openApp failed", e) }
    }

    /** The conversation id of the chat currently on screen, or null if it can't be determined. */
    private fun currentConvoId(root: AccessibilityNodeInfo, profile: AppProfile): String? {
        val name = profile.matchNameId?.let { findById(root, it)?.text?.toString() } ?: return null
        return "${profile.app}:$name"
    }

    private fun appOf(convoId: String): App? =
        runCatching { App.valueOf(convoId.substringBefore(":")) }.getOrNull()

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
