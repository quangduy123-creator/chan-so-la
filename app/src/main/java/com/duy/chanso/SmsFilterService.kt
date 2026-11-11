package com.duy.chanso

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Đọc thông báo của app tin nhắn.
 *
 * Cách hoạt động để tin lạ KHÔNG kêu, KHÔNG hiện:
 *  - Người dùng để thông báo của app Tin nhắn ở chế độ "Im lặng".
 *  - Tin từ số lạ: gỡ thông báo im lặng đó, đánh dấu đã đọc.
 *  - Tin từ danh bạ: gỡ thông báo im lặng, đăng lại thông báo CÓ chuông của app này
 *    (giữ nút Trả lời / mở cuộc trò chuyện của app Tin nhắn).
 *  Nếu app Tin nhắn vẫn để chế độ có chuông thì chỉ gỡ được thông báo sau khi nó đã kêu.
 */
class SmsFilterService : NotificationListenerService() {

    private val smsPackages = setOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        "com.android.messaging",
        "com.oneplus.mms", "com.coloros.mms", "com.oplus.mms",
        "com.vivo.message", "com.huawei.message", "com.hihonor.mms",
        "com.motorola.messaging", "com.asus.message", "com.sonyericsson.conversations"
    )

    private val lastHandled = HashMap<String, String>()   // key thông báo gốc -> nội dung đã xử lý
    private val reposted = HashMap<String, Int>()         // key thông báo gốc -> id thông báo đăng lại

    override fun onListenerConnected() {
        try { activeNotifications?.forEach { handle(it) } } catch (e: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { handle(sbn) } catch (e: Exception) { }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        // App Tin nhắn tự gỡ thông báo (người dùng đã đọc trong app) -> gỡ luôn bản đăng lại
        if (reason == REASON_APP_CANCEL) {
            reposted.remove(sbn.key)?.let { id ->
                getSystemService(NotificationManager::class.java)?.cancel(id)
            }
        }
    }

    private fun isSmsApp(pkg: String) = pkg in smsPackages || pkg == Telephony.Sms.getDefaultSmsPackage(this)

    private fun handle(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        if (!prefs.hideSms) return
        if (!isSmsApp(sbn.packageName)) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = n.extras
        val msg = lastMessage(extras)
        val isMessage = n.category == Notification.CATEGORY_MESSAGE || msg != null
        if (!isMessage) return

        val silent = channelImportance(sbn) < NotificationManager.IMPORTANCE_DEFAULT
        prefs.smsChannelSilent = if (silent) 1 else 0

        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)) return

        val sender = (msg?.first ?: extras.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.trim().orEmpty()
        if (sender.isEmpty()) return
        val text = (msg?.second ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()

        // Bỏ qua khi app Tin nhắn đăng lại cùng một nội dung
        if (lastHandled[sbn.key] == text) return
        lastHandled[sbn.key] = text
        if (lastHandled.size > 200) lastHandled.clear()

        val known = prefs.isWhitelisted(sender) || ContactChecker.isKnownSender(this, sender)

        if (known) {
            // App Tin nhắn đang im lặng -> tự đăng thông báo có chuông thay thế
            if (silent && canPostNotifications()) {
                repostWithSound(sbn, sender, text)
                cancelNotification(sbn.key)
            }
            return
        }

        // Số lạ
        if (prefs.tryDelete) fireAction(n, Notification.Action.SEMANTIC_ACTION_DELETE, listOf("xoá", "xóa", "delete"))
        if (prefs.markRead) fireAction(n, Notification.Action.SEMANTIC_ACTION_MARK_AS_READ, listOf("đã đọc", "mark as read"))
        cancelNotification(sbn.key)
    }

    private fun channelImportance(sbn: StatusBarNotification): Int = try {
        val r = Ranking()
        if (currentRanking.getRanking(sbn.key, r)) r.importance else NotificationManager.IMPORTANCE_DEFAULT
    } catch (e: Exception) { NotificationManager.IMPORTANCE_DEFAULT }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }

    private fun repostWithSound(sbn: StatusBarNotification, sender: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Tin nhắn từ danh bạ", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val orig = sbn.notification
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(sender)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setWhen(orig.`when`)
            .setShowWhen(true)
        orig.contentIntent?.let { b.setContentIntent(it) }
        orig.getLargeIcon()?.let { icon ->
            if (Build.VERSION.SDK_INT >= 28 &&
                (icon.type == Icon.TYPE_BITMAP || icon.type == Icon.TYPE_ADAPTIVE_BITMAP)
            ) b.setLargeIcon(icon)
        }
        orig.actions?.forEach { b.addAction(it) }   // Trả lời, Đánh dấu đã đọc...

        val id = sender.hashCode()
        nm.notify(id, b.build())
        reposted[sbn.key] = id
    }

    /** Lấy (người gửi, nội dung) của tin cuối trong MessagingStyle */
    @Suppress("DEPRECATION")
    private fun lastMessage(extras: Bundle): Pair<CharSequence?, CharSequence?>? {
        val arr: Array<Parcelable> = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = arr.lastOrNull() as? Bundle ?: return null
        val person = last.getParcelable<Person>("sender_person")
        val sender = person?.name ?: last.getCharSequence("sender")
        return Pair(sender, last.getCharSequence("text"))
    }

    private fun fireAction(n: Notification, semantic: Int, keywords: List<String>) {
        val actions = n.actions ?: return
        val action = actions.firstOrNull { it.semanticAction == semantic && it.remoteInputs == null }
            ?: actions.firstOrNull { a ->
                a.remoteInputs == null && keywords.any { k -> a.title?.toString()?.lowercase()?.contains(k) == true }
            }
            ?: return
        try { action.actionIntent?.send() } catch (e: Exception) { }
    }

    companion object {
        const val CHANNEL = "contacts_sms"
    }
}
