package com.opencode.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifications {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_LIFECYCLE = "lifecycle"
    const val CHANNEL_ATTENTION = "attention"
    private val reqSeq = java.util.concurrent.atomic.AtomicInteger(4200)

    private fun req(): Int = reqSeq.incrementAndGet()

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        mk(nm, CHANNEL_STATUS, "Connection", NotificationManager.IMPORTANCE_MIN)
        mk(nm, CHANNEL_LIFECYCLE, "Model finished / errors", NotificationManager.IMPORTANCE_DEFAULT)
        mk(nm, CHANNEL_ATTENTION, "Needs your input (questions & permissions)", NotificationManager.IMPORTANCE_HIGH)
    }

    private fun mk(nm: NotificationManager, id: String, name: String, imp: Int) {
        nm.createNotificationChannel(NotificationChannel(id, name, imp))
    }

    fun openAppIntent(c: Context): PendingIntent {
        val i = Intent(c, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(c, req(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun status(c: Context, text: String): Notification {
        return Notification.Builder(c, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("OpenCode Bridge")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent(c))
            .build()
    }

    fun action(c: Context, intent: Intent, label: String): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            c,
            req(),
            intent.setPackage(c.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action(0, label, pi)
    }

    fun buildFinished(c: Context, title: String, snippet: String): Notification {
        val b = Notification.Builder(c, CHANNEL_LIFECYCLE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Done · $title")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(c))
        if (snippet.isNotBlank()) {
            b.setStyle(Notification.BigTextStyle().bigText(snippet.take(400)))
        }
        return b.build()
    }

    fun buildError(c: Context, title: String, message: String): Notification {
        return Notification.Builder(c, CHANNEL_LIFECYCLE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Error · $title")
            .setContentText(message.take(200))
            .setStyle(Notification.BigTextStyle().bigText(message.take(400)))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(c))
            .build()
    }

    fun buildAttention(c: Context, title: String, body: String, actions: List<Notification.Action>): Notification {
        val b = Notification.Builder(c, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body.take(180))
            .setStyle(Notification.BigTextStyle().bigText(body.take(600)))
            .setAutoCancel(false)
            .setContentIntent(openAppIntent(c))
        for (a in actions.take(3)) b.addAction(a)
        return b.build()
    }
}
