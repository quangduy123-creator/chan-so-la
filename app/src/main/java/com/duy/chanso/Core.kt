package com.duy.chanso

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils

/** Cài đặt người dùng */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var blockCalls: Boolean
        get() = sp.getBoolean("blockCalls", true)
        set(v) { sp.edit().putBoolean("blockCalls", v).apply() }

    var hideSms: Boolean
        get() = sp.getBoolean("hideSms", true)
        set(v) { sp.edit().putBoolean("hideSms", v).apply() }

    var markRead: Boolean
        get() = sp.getBoolean("markRead", true)
        set(v) { sp.edit().putBoolean("markRead", v).apply() }

    var tryDelete: Boolean
        get() = sp.getBoolean("tryDelete", false)
        set(v) { sp.edit().putBoolean("tryDelete", v).apply() }

    /** -1 chưa biết, 0 app Tin nhắn còn kêu, 1 đã để Im lặng (do SmsFilterService ghi nhận) */
    var smsChannelSilent: Int
        get() = sp.getInt("smsSilent", -1)
        set(v) { sp.edit().putInt("smsSilent", v).apply() }

    var whitelistRaw: String
        get() = sp.getString("whitelist", "") ?: ""
        set(v) { sp.edit().putString("whitelist", v).apply() }

    private fun whitelist(): List<String> =
        whitelistRaw.split(',', '\n', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /** Số/tên chứa một từ khoá trong danh sách cho phép thì không chặn */
    fun isWhitelisted(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        val v = s.lowercase()
        val digits = s.filter { it.isDigit() }
        return whitelist().any { k ->
            v.contains(k) || (k.all { it.isDigit() } && digits.isNotEmpty() && digits.contains(k))
        }
    }
}

/** Kiểm tra số/tên có trong danh bạ không */
object ContactChecker {
    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun isKnownNumber(ctx: Context, number: String?): Boolean {
        if (number.isNullOrBlank()) return false            // số ẩn danh -> coi là lạ
        if (!hasPermission(ctx)) return true                 // chưa có quyền -> không chặn (an toàn)
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        } catch (e: Exception) { true }
    }

    fun isKnownName(ctx: Context, name: String): Boolean {
        if (!hasPermission(ctx)) return true
        return try {
            ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ?",
                arrayOf(name), null
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) { true }
    }

    fun looksLikeNumber(s: String): Boolean {
        val digits = s.count { it.isDigit() }
        return digits >= 3 && s.all { it.isDigit() || it in "+-() ." }
    }

    /** App SMS hiển thị tên danh bạ nếu số đã lưu, ngược lại hiển thị số hoặc brandname */
    fun isKnownSender(ctx: Context, sender: String): Boolean =
        if (looksLikeNumber(sender)) isKnownNumber(ctx, sender) else isKnownName(ctx, sender)
}

/** Dự phòng: xoá lịch sử cuộc gọi (một số máy vẫn ghi log dù đã yêu cầu bỏ qua) */
object CallLogCleaner {
    fun canWrite(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun scheduleCleanup(ctx: Context, number: String?) {
        if (!canWrite(ctx)) return
        val h = Handler(Looper.getMainLooper())
        for (delay in longArrayOf(3_000, 10_000, 30_000)) {
            h.postDelayed({ deleteRecent(ctx, number) }, delay)
        }
    }

    private fun deleteRecent(ctx: Context, number: String?) {
        try {
            val since = System.currentTimeMillis() - 5 * 60 * 1000
            val cr = ctx.contentResolver
            cr.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER),
                "${CallLog.Calls.DATE} > ?", arrayOf(since.toString()), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val n = c.getString(1) ?: ""
                    val match = if (number.isNullOrBlank()) n.isBlank() || n.startsWith("-")
                    else PhoneNumberUtils.compare(n, number)
                    if (match) cr.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} = ?", arrayOf(id.toString()))
                }
            }
        } catch (e: Exception) { /* thiếu quyền hoặc máy không cho phép */ }
    }

    /** Xoá toàn bộ lịch sử cuộc gọi cũ của các số không có trong danh bạ. Trả về số mục đã xoá. */
    fun purgeUnknown(ctx: Context): Int {
        if (!canWrite(ctx)) return -1
        val prefs = Prefs(ctx)
        val cache = HashMap<String, Boolean>()
        val toDelete = ArrayList<Long>()
        val cr = ctx.contentResolver
        cr.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER), null, null, null)
            ?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(1) ?: ""
                    val known = cache.getOrPut(n) {
                        prefs.isWhitelisted(n) || ContactChecker.isKnownNumber(ctx, n.takeUnless { it.startsWith("-") })
                    }
                    if (!known) toDelete.add(c.getLong(0))
                }
            }
        toDelete.forEach { cr.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} = ?", arrayOf(it.toString())) }
        return toDelete.size
    }
}
