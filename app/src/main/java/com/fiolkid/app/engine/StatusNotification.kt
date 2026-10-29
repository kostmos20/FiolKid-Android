package com.fiolkid.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.fiolkid.app.MainActivity
import com.fiolkid.app.R

/**
 * Уведомление, пока FiolKid включён: что он работает, сколько трафика прошло и кнопка «Выключить».
 * Заодно говорит Android, что программа занята делом — без него телефон (особенно Xiaomi) выгружает её из фона.
 */
object StatusNotification {
    const val ID = 1
    private const val CHANNEL = "status"

    fun build(context: Context, title: String, text: String): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "FiolKid включён", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Показывается, пока работает обход блокировок"
                    setShowBadge(false)
                },
            )

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, FiolVpnService::class.java).setAction(FiolVpnService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(context)
        return builder
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Выключить", stop).build())
            .build()
    }

    fun update(context: Context, title: String, text: String) {
        context.getSystemService(NotificationManager::class.java).notify(ID, build(context, title, text))
    }
}
