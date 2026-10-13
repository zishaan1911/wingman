package com.wingman.assist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant

/**
 * Always-on-top status chip + the controls that matter:
 *  • KILL SWITCH — tap the chip (when nothing is awaiting approval) to pause/resume everything.
 *  • APPROVALS   — when a message is held for your approval (big move, or SUPERVISED mode),
 *                  the chip shows the draft; TAP = approve & schedule, LONG-PRESS = reject.
 *
 * Deliberately minimal. Grow into a panel (edit draft before sending, snooze, send-now,
 * per-match toggles) as needed. Open DevPanelActivity for all tunables.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var label: TextView
    private var container: LinearLayout? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        goForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        label = TextView(this).apply {
            text = status()
            setTextColor(Color.WHITE)
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.argb(210, 0, 0, 0))
            setOnClickListener { onTap() }
            setOnLongClickListener { onLongPress(); true }
        }
        container = LinearLayout(this).apply { addView(label) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 120 }
        wm.addView(container, params)
    }

    override fun onDestroy() {
        container?.let { runCatching { wm.removeView(it) } }
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Promote to a foreground service so Android doesn't reap it (and the overlay stays up). */
    private fun goForeground() {
        val chId = "wingman"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(chId) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(chId, "Wingman", NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
        val notif: Notification = Notification.Builder(this, chId)
            .setContentTitle("Wingman running")
            .setContentText("Tap the floating control to pause.")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            // If FGS start is restricted, keep running as a normal service rather than crash.
        }
    }

    private fun onTap() {
        val pending = pendingApproval
        if (pending != null) { approveCb?.invoke(pending); clearApproval() }
        else { killSwitchEngaged = !killSwitchEngaged; refresh() }
    }

    private fun onLongPress() {
        val pending = pendingApproval
        if (pending != null) { rejectCb?.invoke(pending); clearApproval() }
    }

    private fun status(): String = buildString {
        pendingApproval?.let {
            append("APPROVE? tap=yes · hold=no\n")
            append("→ ${it.matchName} [${it.escalation}]\n")
            append(it.text.take(80))
            return@buildString
        }
        append(if (killSwitchEngaged) "⏸ PAUSED (tap to resume)" else "▶ Wingman")
        pendingLine?.let { append("\n").append(it) }
        swipeLine?.let { append("\n").append(it) }
    }

    private fun refresh() { label.post { label.text = status() } }

    companion object {
        private const val NOTIF_ID = 1001
        @Volatile var killSwitchEngaged = false
        private var instance: OverlayService? = null
        private var pendingLine: String? = null
        private var swipeLine: String? = null

        private var pendingApproval: ScheduledReply? = null
        private var approveCb: ((ScheduledReply) -> Unit)? = null
        private var rejectCb: ((ScheduledReply) -> Unit)? = null

        fun start(ctx: Context) {
            if (!AndroidSettings.canDrawOverlays(ctx)) return
            ctx.startService(Intent(ctx, OverlayService::class.java))
        }

        fun requestApproval(
            reply: ScheduledReply,
            onApprove: (ScheduledReply) -> Unit,
            onReject: (ScheduledReply) -> Unit,
        ) {
            pendingApproval = reply; approveCb = onApprove; rejectCb = onReject
            instance?.refresh()
        }

        private fun clearApproval() {
            pendingApproval = null; approveCb = null; rejectCb = null
            instance?.refresh()
        }

        fun showPending(match: String, text: String, at: Instant) {
            val mins = ((at.toEpochMilli() - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
            pendingLine = "→ $match in ${mins}m:\n" + text.take(140)
            instance?.refresh()
        }

        fun clearPending() { pendingLine = null; instance?.refresh() }

        fun showSwipe(d: SwipeDecision) {
            swipeLine = (if (d.like) "♥ " else "✕ ") + d.reason.take(40)
            instance?.refresh()
        }
    }
}
