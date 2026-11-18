package com.duy.chanso

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Màn hình chat 1 cuộc trò chuyện (hoặc soạn tin mới nếu chưa có người nhận) */
class ConversationActivity : BaseActivity() {

    companion object {
        @Volatile var visibleThreadId = -1L
        private const val REQ_PICK = 201

        fun open(ctx: Context, threadId: Long, address: String): Intent =
            Intent(ctx, ConversationActivity::class.java)
                .putExtra("tid", threadId)
                .putExtra("addr", address)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private var threadId = -1L
    private var address = ""
    private lateinit var msgBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var toRow: LinearLayout
    private lateinit var toField: EditText
    private var observing = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { load() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        threadId = intent.getLongExtra("tid", -1L)
        address = intent.getStringExtra("addr") ?: ""

        // Mở từ app khác: sms:0901234567?body=...
        var prefill: String? = null
        val data = intent.data
        if (data != null) {
            address = RespondService.addressFrom(data) ?: ""
            prefill = intent.getStringExtra("sms_body")
                ?: data.schemeSpecificPart?.substringAfter("body=", "")?.takeIf { it.isNotEmpty() }
        }
        if (threadId < 0 && address.isNotBlank() && isDefaultSms()) threadId = SmsRepo.threadIdFor(this, address)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(topBar(titleText(), true))

        // Ô nhập người nhận (khi soạn tin mới)
        toRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), dp(4))
        }
        toField = EditText(this).apply {
            hint = "Số điện thoại"
            inputType = InputType.TYPE_CLASS_PHONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        toRow.addView(toField)
        toRow.addView(textButton("Danh bạ") {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_PICK
            )
        })
        toRow.visibility = if (address.isBlank()) View.VISIBLE else View.GONE
        root.addView(toRow)

        // Danh sách tin
        msgBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        scroll = ScrollView(this).apply {
            addView(msgBox)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)

        // Ô soạn tin
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), dp(6))
        }
        input = EditText(this).apply {
            hint = "Nhắn tin"
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(GRAY_BG) }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        prefill?.let { input.setText(it) }
        bottom.addView(input)
        bottom.addView(Button(this).apply {
            text = "Gửi"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(PRIMARY) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44))
                .apply { leftMargin = dp(8) }
            setOnClickListener { send() }
        })
        root.addView(bottom)

        setContentView(root)
        applyInsets(root)
    }

    private fun titleText(): String =
        if (address.isBlank()) "Tin nhắn mới"
        else ContactChecker.displayName(this, address) ?: address

    override fun onResume() {
        super.onResume()
        visibleThreadId = threadId
        if (!observing) {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            observing = true
        }
        load()
    }

    override fun onPause() {
        super.onPause()
        visibleThreadId = -1L
        if (observing) { contentResolver.unregisterContentObserver(observer); observing = false }
    }

    private fun load() {
        val tid = threadId
        if (tid < 0) return
        Notifier.cancel(this, tid)
        Thread {
            val msgs = try { SmsRepo.messages(this, tid) } catch (e: Exception) { emptyList() }
            if (msgs.any { !it.read } && isDefaultSms()) SmsRepo.markRead(this, tid)
            runOnUiThread { render(msgs) }
        }.start()
    }

    private fun render(msgs: List<SmsRepo.Msg>) {
        msgBox.removeAllViews()
        val maxW = (resources.displayMetrics.widthPixels * 0.78).toInt()
        for (m in msgs) {
            val out = m.type != Telephony.Sms.MESSAGE_TYPE_INBOX
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (out) Gravity.END else Gravity.START
                setPadding(0, dp(3), 0, dp(3))
            }
            wrap.addView(TextView(this).apply {
                text = m.body
                textSize = 16f
                maxWidth = maxW
                setTextIsSelectable(true)
                setTextColor(if (out) Color.WHITE else Color.BLACK)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(if (out) PRIMARY else GRAY_BG)
                }
            })
            val status = when (m.type) {
                Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> " · Đang gửi…"
                Telephony.Sms.MESSAGE_TYPE_FAILED -> " · ⚠ Gửi lỗi – chạm để gửi lại"
                else -> ""
            }
            wrap.addView(TextView(this).apply {
                text = fmtTime(m.date) + status
                textSize = 11f
                setTextColor(if (m.type == Telephony.Sms.MESSAGE_TYPE_FAILED) Color.RED else GRAY_TEXT)
                setPadding(dp(6), dp(2), dp(6), 0)
                if (m.type == Telephony.Sms.MESSAGE_TYPE_FAILED) setOnClickListener { resend(m) }
            })
            msgBox.addView(wrap)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun resend(m: SmsRepo.Msg) {
        val to = address
        if (to.isBlank()) return
        Thread {
            SmsRepo.deleteMessage(this, m.id)
            SmsSender.send(this, to, m.body)
        }.start()
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val to = if (address.isNotBlank()) address else toField.text.toString().trim()
        if (to.isBlank()) { toast("Nhập số người nhận"); return }
        if (!isDefaultSms()) { toast("Cần đặt app làm app Tin nhắn mặc định trước"); return }
        input.setText("")
        Thread {
            SmsSender.send(this, to, text)
            val tid = if (threadId >= 0) threadId else SmsRepo.threadIdFor(this, to)
            runOnUiThread {
                if (address.isBlank()) {
                    address = to
                    threadId = tid
                    visibleThreadId = tid
                    toRow.visibility = View.GONE
                    titleView?.text = titleText()
                }
                load()
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
                ?.use { c -> if (c.moveToFirst()) toField.setText(c.getString(0)) }
        } catch (e: Exception) { }
    }
}
