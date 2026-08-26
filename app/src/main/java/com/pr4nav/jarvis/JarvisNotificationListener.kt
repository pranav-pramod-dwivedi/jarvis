package com.pr4nav.jarvis

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

class JarvisNotificationListener : NotificationListenerService() {

    data class Snap(
        val key: String, val pkg: String, val title: String,
        val text: String, val bigText: String,
        val postedAt: Long, val ongoing: Boolean, val clearable: Boolean
    )

    companion object {
        private val live = ConcurrentHashMap<String, Snap>()
        @Volatile var connected: Boolean = false
        @Volatile var instance: JarvisNotificationListener? = null

        fun snapshot(): List<Snap> = live.values.sortedByDescending { it.postedAt }

        fun clearCache() { live.clear() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onListenerConnected() {
        connected = true
        activeNotifications?.forEach { upsert(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = upsert(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        live.remove(sbn.key)
    }

    override fun onDestroy() {
        connected = false
        instance = null
        super.onDestroy()
    }

    private fun upsert(sbn: StatusBarNotification) {
        val ex = sbn.notification.extras
        val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        val big = ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        live[sbn.key] = Snap(
            key = sbn.key, pkg = sbn.packageName, title = title,
            text = text, bigText = big, postedAt = sbn.postTime,
            ongoing = sbn.isOngoing, clearable = sbn.isClearable
        )
    }
}
