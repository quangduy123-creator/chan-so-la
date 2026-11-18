package com.duy.chanso

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import android.telephony.TelephonyManager

/** Tin SMS đến (chỉ app mặc định nhận được broadcast này) */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val first = parts[0] ?: return
        val address = first.displayOriginatingAddress ?: first.originatingAddress ?: return
        val body = parts.joinToString("") { it?.displayMessageBody ?: it?.messageBody ?: "" }
        val sentAt = first.timestampMillis
        val subId = intent.getIntExtra(
            "android.telephony.extra.SUBSCRIPTION_INDEX",
            intent.getIntExtra("subscription", -1)
        )
        val app = ctx.applicationContext
        val pending = goAsync()
        Thread {
            try {
                // Số lạ: bỏ luôn, không lưu, không báo
                if (!SenderRule.shouldBlockSms(app, address)) {
                    val tid = SmsRepo.insertIncoming(app, address, body, sentAt, subId)
                    if (tid >= 0) Notifier.showIncoming(app, tid, address)
                }
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** MMS: bản này chưa hỗ trợ tải MMS, chỉ báo cho người dùng biết */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notifier.showMmsNotice(ctx.applicationContext)
    }
}

/** Trả lời nhanh khi từ chối cuộc gọi bằng tin nhắn */
class RespondService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            val address = addressFrom(intent.data)
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!address.isNullOrBlank() && !text.isNullOrBlank()) {
                val app = applicationContext
                Thread { try { SmsSender.send(app, address, text) } catch (e: Exception) { } }.start()
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        fun addressFrom(uri: Uri?): String? =
            uri?.schemeSpecificPart?.substringBefore('?')?.split(',', ';')?.firstOrNull()?.trim()
    }
}
