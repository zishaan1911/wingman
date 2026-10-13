package com.wingman.assist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Pushes phone notifications for things you actually need to know about — so you don't have
 * to watch the overlay. Tapping a notification opens the dev panel / app.
 *
 * "Important" means: a reply is waiting for your approval (explicit handoff, a big move, or
 * supervised mode), or a safety flag fired (possible under-18 match). Routine replies don't
 * notify — only things that need your eyes.
 */
class Notifier(private val ctx: Context) {

    companion object {
        private const val CHANNEL = "wingman_alerts"
        private const val ID_APPROVAL = 2001
        private const val ID_ALERT = 2002
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Wingman alerts", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "Approvals and safety flags" }
                )
            }
        }
    }

    /** A drafted message is waiting for your approve/edit/send (tap to open). */
    fun approvalNeeded(matchName: String, preview: String, why: String) {
        post(ID_APPROVAL, "Approval needed · $matchName", "[$why] ${preview.take(90)}")
    }

    /** A reply is due but its chat isn't open — open the chat and it sends automatically. */
    fun readyToSend(matchName: String, preview: String) {
        post(ID_APPROVAL, "Ready to send · $matchName", "Open the chat to send: ${preview.take(90)}")
    }

    /** A safety flag (e.g. possible under-18) — surfaced, not acted on automatically. */
    fun alert(title: String, body: String) {
        post(ID_ALERT, title, body)
    }

    private fun post(id: Int, title: String, body: String) {
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        val tap = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, DevPanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try { mgr.notify(id, n) } catch (_: Exception) { /* notifications may be disabled */ }
    }
}
