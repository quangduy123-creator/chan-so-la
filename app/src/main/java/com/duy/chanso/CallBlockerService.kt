package com.duy.chanso

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Android gọi service này với MỌI cuộc gọi đến (khi app giữ vai trò "Chặn cuộc gọi/Call screening").
 * Số không có trong danh bạ -> ngắt máy, không ghi lịch sử, không hiện thông báo.
 */
class CallBlockerService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val prefs = Prefs(this)
        val incoming = details.callDirection == Call.Details.DIRECTION_INCOMING
        val number = details.handle?.schemeSpecificPart

        val allow = !incoming ||
            !prefs.blockCalls ||
            prefs.isWhitelisted(number) ||
            ContactChecker.isKnownNumber(this, number)

        if (allow) {
            respondToCall(details, CallResponse.Builder().build())
            return
        }

        val response = CallResponse.Builder()
            .setDisallowCall(true)    // không cho cuộc gọi vào
            .setRejectCall(true)      // ngắt như bấm từ chối
            .setSkipCallLog(true)     // không ghi vào lịch sử cuộc gọi
            .setSkipNotification(true) // không hiện "cuộc gọi nhỡ"
            .build()
        respondToCall(details, response)

        BlockLog.add(this, "CALL", number ?: "Số ẩn danh", "Đã chặn cuộc gọi")
        CallLogCleaner.scheduleCleanup(applicationContext, number)
    }
}
