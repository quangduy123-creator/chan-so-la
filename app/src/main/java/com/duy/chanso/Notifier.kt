package com.duy.chanso

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build

object Notifier {
    private const val CHANNEL = "messages"
    private const val KEY_REPLY = "reply"
    const val ACTION_REPLY = "com.duy.chanso.REPLY"
    const val ACTION_READ = "com.duy.chanso.READ"
    private const val MMS_ID = 999_999

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(ctx: Context) {
        val nm = nm(ctx) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Tin nhắn", NotificationManager.IMPORTANCE_HIGH)
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)
        }
    }

    fun showIncoming(ctx: Context, threadId: Long, address: String) {
        if (ConversationActivity.visibleThreadId == threadId) return  // đang mở cuộc trò chuyện này
        ensureChannel(ctx)
        val name = ContactChecker.displayName(ctx, address) ?: address
        val me = Person.Builder().setName("Bạn").build()
        val sender = Person.Builder().setName(name).setKey(address).build()
        val style = Notification.MessagingStyle(me)
        val unread = SmsRepo.unreadInbox(ctx, threadId).takeLast(6)
        if (unread.isEmpty()) return
        unread.forEach { style.addMessage(it.body, it.date, sender) }

        val id = threadId.toInt()
        val open = PendingIntent.getActivity(
            ctx, id, ConversationActivity.open(ctx, threadId, address),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val replyPI = PendingIntent.getBroadcast(
            ctx, id,
            actionIntent(ctx, ACTION_REPLY, threadId, address),
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val readPI = PendingIntent.getBroadcast(
            ctx, id + 1_000_000,
            actionIntent(ctx, ACTION_READ, threadId, address),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val reply = Notification.Action.Builder(
            Icon.createWithResource("android", android.R.drawable.ic_menu_send), "Trả lời", replyPI
        )
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Trả lời").build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .build()
        val read = Notification.Action.Builder(
            Icon.createWithResource("android", android.R.drawable.ic_menu_view), "Đã đọc", readPI
        )
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()

        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(style)
            .setContentTitle(name)
            .setContentText(unread.last().body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setNumber(unread.size)
            .addAction(reply)
            .addAction(read)
            .build()
        try { nm(ctx)?.notify(id, n) } catch (e: SecurityException) { }
    }

    fun showMmsNotice(ctx: Context) {
        ensureChannel(ctx)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("Có tin MMS")
            .setContentText("Bản này chưa hỗ trợ MMS (tin có ảnh / nhóm).")
            .setAutoCancel(true)
            .build()
        try { nm(ctx)?.notify(MMS_ID, n) } catch (e: SecurityException) { }
    }

    fun cancel(ctx: Context, threadId: Long) { nm(ctx)?.cancel(threadId.toInt()) }

    private fun actionIntent(ctx: Context, action: String, threadId: Long, address: String) =
        Intent(ctx, NotifActionReceiver::class.java)
            .setAction(action)
            .putExtra("tid", threadId)
            .putExtra("addr", address)

    fun replyText(intent: Intent): String? =
        RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
}

/** Bấm "Trả lời" / "Đã đọc" trên thông báo */
class NotifActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val tid = intent.getLongExtra("tid", -1)
        val addr = intent.getStringExtra("addr") ?: ""
        val app = ctx.applicationContext
        val text = if (intent.action == Notifier.ACTION_REPLY) Notifier.replyText(intent) else null
        val pending = goAsync()
        Thread {
            try {
                if (!text.isNullOrBlank() && addr.isNotBlank()) SmsSender.send(app, addr, text)
                if (tid >= 0) SmsRepo.markRead(app, tid)
                Notifier.cancel(app, tid)
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}
