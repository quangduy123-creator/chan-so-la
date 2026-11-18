package com.duy.chanso

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads

/** Đọc / ghi kho tin nhắn của hệ thống (chỉ app Tin nhắn mặc định mới ghi được) */
object SmsRepo {

    data class ThreadInfo(
        val id: Long,
        val address: String,
        val snippet: String,
        val date: Long,
        val unread: Int,
        val lastType: Int
    )

    data class Msg(val id: Long, val body: String, val date: Long, val type: Int, val read: Boolean)

    fun threads(ctx: Context): List<ThreadInfo> {
        val order = LinkedHashMap<Long, ThreadInfo>()
        val unread = HashMap<Long, Int>()
        ctx.contentResolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.READ, Sms.TYPE),
            null, null, "${Sms.DATE} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val tid = c.getLong(0)
                val addr = c.getString(1) ?: ""
                val type = c.getInt(5)
                if (c.getInt(4) == 0 && type == Sms.MESSAGE_TYPE_INBOX) unread[tid] = (unread[tid] ?: 0) + 1
                val cur = order[tid]
                if (cur == null) {
                    order[tid] = ThreadInfo(tid, addr, c.getString(2) ?: "", c.getLong(3), 0, type)
                } else if (cur.address.isBlank() && addr.isNotBlank()) {
                    order[tid] = cur.copy(address = addr)
                }
            }
        }
        return order.values.map { it.copy(unread = unread[it.id] ?: 0) }
    }

    fun messages(ctx: Context, threadId: Long, limit: Int = 300): List<Msg> {
        val out = ArrayList<Msg>()
        ctx.contentResolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms._ID, Sms.BODY, Sms.DATE, Sms.TYPE, Sms.READ),
            "${Sms.THREAD_ID} = ?", arrayOf(threadId.toString()),
            "${Sms.DATE} DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out.add(Msg(c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getInt(3), c.getInt(4) != 0))
            }
        }
        out.reverse()
        return out
    }

    fun unreadInbox(ctx: Context, threadId: Long): List<Msg> =
        messages(ctx, threadId, 50).filter { it.type == Sms.MESSAGE_TYPE_INBOX && !it.read }

    fun markRead(ctx: Context, threadId: Long) {
        val v = ContentValues().apply { put(Sms.READ, 1); put(Sms.SEEN, 1) }
        try {
            ctx.contentResolver.update(
                Sms.CONTENT_URI, v,
                "${Sms.THREAD_ID} = ? AND ${Sms.READ} = 0", arrayOf(threadId.toString())
            )
        } catch (e: Exception) { }
    }

    fun markAllRead(ctx: Context) {
        val v = ContentValues().apply { put(Sms.READ, 1); put(Sms.SEEN, 1) }
        try { ctx.contentResolver.update(Sms.CONTENT_URI, v, "${Sms.READ} = 0", null) } catch (e: Exception) { }
    }

    fun deleteThread(ctx: Context, threadId: Long) {
        try {
            ctx.contentResolver.delete(Uri.withAppendedPath(Sms.Conversations.CONTENT_URI, threadId.toString()), null, null)
        } catch (e: Exception) { }
    }

    fun deleteMessage(ctx: Context, id: Long) {
        try { ctx.contentResolver.delete(Uri.withAppendedPath(Sms.CONTENT_URI, id.toString()), null, null) } catch (e: Exception) { }
    }

    fun threadIdFor(ctx: Context, address: String): Long =
        try { Threads.getOrCreateThreadId(ctx, address) } catch (e: Exception) { -1L }

    /** Lưu tin đến vào hộp thư. Trả về thread id (hoặc -1). */
    fun insertIncoming(ctx: Context, address: String, body: String, dateSent: Long, subId: Int): Long {
        val v = ContentValues().apply {
            put(Sms.ADDRESS, address)
            put(Sms.BODY, body)
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.DATE_SENT, dateSent)
            put(Sms.READ, 0)
            put(Sms.SEEN, 0)
            put(Sms.TYPE, Sms.MESSAGE_TYPE_INBOX)
            if (subId >= 0) put(Sms.SUBSCRIPTION_ID, subId)
        }
        val uri = try { ctx.contentResolver.insert(Sms.Inbox.CONTENT_URI, v) } catch (e: Exception) { null } ?: return -1
        return threadOf(ctx, uri)
    }

    fun insertOutgoing(ctx: Context, address: String, body: String, subId: Int): Uri? {
        val v = ContentValues().apply {
            put(Sms.ADDRESS, address)
            put(Sms.BODY, body)
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
            put(Sms.TYPE, Sms.MESSAGE_TYPE_OUTBOX)
            if (subId >= 0) put(Sms.SUBSCRIPTION_ID, subId)
        }
        return try { ctx.contentResolver.insert(Sms.Outbox.CONTENT_URI, v) } catch (e: Exception) { null }
    }

    fun setType(ctx: Context, uri: Uri, type: Int) {
        try {
            ctx.contentResolver.update(uri, ContentValues().apply { put(Sms.TYPE, type) }, null, null)
        } catch (e: Exception) { }
    }

    private fun threadOf(ctx: Context, uri: Uri): Long =
        try {
            ctx.contentResolver.query(uri, arrayOf(Sms.THREAD_ID), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
        } catch (e: Exception) { -1L }

    /**
     * Xoá các cuộc trò chuyện cũ từ số lạ (chỉ những cuộc mà bạn CHƯA từng nhắn đi).
     * Trả về số cuộc trò chuyện đã xoá.
     */
    fun purgeSpam(ctx: Context): Int {
        val hasOutgoing = HashSet<Long>()
        val addrOf = HashMap<Long, String>()
        ctx.contentResolver.query(
            Sms.CONTENT_URI, arrayOf(Sms.THREAD_ID, Sms.ADDRESS, Sms.TYPE), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val tid = c.getLong(0)
                if (c.getInt(2) != Sms.MESSAGE_TYPE_INBOX) hasOutgoing.add(tid)
                val a = c.getString(1)
                if (!a.isNullOrBlank() && !addrOf.containsKey(tid)) addrOf[tid] = a
            }
        }
        var n = 0
        for ((tid, addr) in addrOf) {
            if (tid in hasOutgoing) continue
            if (SenderRule.shouldBlockSms(ctx, addr, ignoreToggle = true)) {
                deleteThread(ctx, tid)
                n++
            }
        }
        return n
    }
}
