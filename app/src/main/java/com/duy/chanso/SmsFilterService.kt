package com.duy.chanso

import android.app.Notification
import android.app.Person
import android.os.Bundle
import android.os.Parcelable
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Đọc thông báo của app tin nhắn. Nếu người gửi không có trong danh bạ:
 *  - gỡ thông báo (không hiện, không rung)
 *  - tuỳ chọn: bấm "Đánh dấu đã đọc" / "Xoá" nếu app SMS có nút đó trên thông báo
 *  - lưu nội dung vào nhật ký trong app (để vẫn xem được OTP nếu cần)
 */
class SmsFilterService : NotificationListenerService() {

    private val smsPackages = setOf(
        "com.google.android.apps.messaging",   // Google Messages
        "com.samsung.android.messaging",       // Samsung
        "com.android.mms",                     // Xiaomi / AOSP
        "com.android.messaging",
        "com.oneplus.mms", "com.coloros.mms", "com.oplus.mms", // Oppo / OnePlus / Realme
        "com.vivo.message", "com.huawei.message", "com.hihonor.mms",
        "com.motorola.messaging", "com.asus.message", "com.sonyericsson.conversations"
    )

    private val lastHandled = HashMap<String, String>()

    override fun onListenerConnected() {
        try { activeNotifications?.forEach { handle(it) } } catch (e: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { handle(sbn) } catch (e: Exception) { }
    }

    private fun handle(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        if (!prefs.hideSms) return

        val pkg = sbn.packageName
        if (pkg !in smsPackages && pkg != Telephony.Sms.getDefaultSmsPackage(this)) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = n.extras
        val messages = lastMessage(extras)
        val isMessage = n.category == Notification.CATEGORY_MESSAGE || messages != null
        if (!isMessage) return
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)) return // bỏ qua nhóm

        val sender = (messages?.first ?: extras.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.trim().orEmpty()
        if (sender.isEmpty()) return

        if (prefs.isWhitelisted(sender) || ContactChecker.isKnownSender(this, sender)) return

        val text = (messages?.second ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()

        // Tự bấm các nút hành động trên thông báo (nếu app SMS có)
        if (prefs.tryDelete) fireAction(n, Notification.Action.SEMANTIC_ACTION_DELETE, listOf("xoá", "xóa", "delete"))
        if (prefs.markRead) fireAction(n, Notification.Action.SEMANTIC_ACTION_MARK_AS_READ, listOf("đã đọc", "mark as read", "read"))

        cancelNotification(sbn.key)

        // Tránh ghi trùng khi app SMS cập nhật lại cùng một thông báo
        if (lastHandled[sbn.key] != text) {
            lastHandled[sbn.key] = text
            if (lastHandled.size > 200) lastHandled.clear()
            BlockLog.add(this, "SMS", sender, text)
        }
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
}
