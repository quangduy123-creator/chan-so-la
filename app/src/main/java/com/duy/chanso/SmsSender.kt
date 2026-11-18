package com.duy.chanso

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

object SmsSender {
    const val ACTION_SENT = "com.duy.chanso.SMS_SENT"

    /** Gửi tin. Gọi từ luồng phụ. */
    fun send(ctx: Context, address: String, body: String) {
        val subId = pickSubscription(ctx)
        val uri = SmsRepo.insertOutgoing(ctx, address, body, subId)
        val reqCode = (uri?.lastPathSegment?.toLongOrNull() ?: System.currentTimeMillis()).toInt()
        val sentIntent = Intent(ctx, SentReceiver::class.java).setAction(ACTION_SENT).setData(uri)
        val sentPI = PendingIntent.getBroadcast(
            ctx, reqCode, sentIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            val sm = smsManager(ctx, subId)
            val parts = sm.divideMessage(body)
            if (parts.size > 1) {
                val pis = ArrayList<PendingIntent>()
                repeat(parts.size) { pis.add(sentPI) }
                sm.sendMultipartTextMessage(address, null, parts, pis, null)
            } else {
                sm.sendTextMessage(address, null, body, sentPI, null)
            }
        } catch (e: Exception) {
            if (uri != null) SmsRepo.setType(ctx, uri, Sms.MESSAGE_TYPE_FAILED)
        }
    }

    private fun pickSubscription(ctx: Context): Int {
        val def = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (def != SubscriptionManager.INVALID_SUBSCRIPTION_ID) return def
        // "Luôn hỏi" -> dùng SIM đầu tiên đang hoạt động
        return try {
            ctx.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId ?: -1
        } catch (e: SecurityException) { -1 }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(ctx: Context, subId: Int): SmsManager {
        return if (Build.VERSION.SDK_INT >= 31) {
            val base = ctx.getSystemService(SmsManager::class.java)
            if (subId >= 0) base.createForSubscriptionId(subId) else base
        } else {
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }
}

/** Nhận kết quả gửi -> cập nhật trạng thái tin: đã gửi / lỗi */
class SentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val uri = intent.data ?: return
        val type = if (resultCode == Activity.RESULT_OK) Sms.MESSAGE_TYPE_SENT else Sms.MESSAGE_TYPE_FAILED
        SmsRepo.setType(ctx, uri, type)
    }
}
