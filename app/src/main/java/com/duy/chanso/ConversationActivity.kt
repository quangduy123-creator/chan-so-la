package com.duy.chanso

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

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
    private lateinit var header: LinearLayout
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

        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        root.addView(header)
        buildHeader()

        // Ô nhập người nhận (khi soạn tin mới)
        toRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(8), dp(4))
        }
        toRow.addView(text("Đến", 16f, TEXT2))
        toField = EditText(this).apply {
            hint = "Nhập tên hoặc số điện thoại"
            inputType = InputType.TYPE_CLASS_PHONE
            textSize = 16f
            background = null
            setPadding(dp(12), dp(12), dp(8), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        toRow.addView(toField)
        toRow.addView(textButton("Danh bạ") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_PICK)
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
            setPadding(dp(12), dp(6), dp(10), dp(10))
        }
        input = EditText(this).apply {
            hint = "Tin nhắn văn bản"
            textSize = 16f
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = rounded(GRAY_BG, 26)
            setPadding(dp(20), dp(13), dp(20), dp(13))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        prefill?.let { input.setText(it) }
        bottom.addView(input)
        bottom.addView(FrameLayout(this).apply {
            background = circle(PRIMARY)
            isClickable = true
            setOnClickListener { send() }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_send)
                setColorFilter(Color.WHITE)
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            })
        }, LinearLayout.LayoutParams(dp(50), dp(50)).apply { leftMargin = dp(8) })
        root.addView(bottom)

        setContentView(root)
        applyInsets(root, SHEET)
    }

    private fun buildHeader() {
        header.removeAllViews()
        header.addView(icon(R.drawable.ic_back) { finish() })
        if (address.isBlank()) {
            header.addView(text("Cuộc trò chuyện mới", 22f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            return
        }
        val name = ContactChecker.displayName(this, address)
        header.addView(avatar(name ?: address, name != null, 40))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            addView(text(name ?: address, 19f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            if (name != null) addView(text(address, 13f, TEXT2))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (SenderRule.isPersonalNumber(address)) {
            header.addView(icon(R.drawable.ic_phone) {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$address")))
            })
        }
        lateinit var more: View
        more = icon(R.drawable.ic_more) { showMenu(more) }
        header.addView(more)
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Xoá cuộc trò chuyện")
        pm.setOnMenuItemClickListener {
            if (threadId >= 0 && isDefaultSms()) {
                AlertDialog.Builder(this)
                    .setTitle("Xoá cuộc trò chuyện?")
                    .setNegativeButton("Huỷ", null)
                    .setPositiveButton("Xoá") { _, _ ->
                        Thread { SmsRepo.deleteThread(this, threadId); runOnUiThread { finish() } }.start()
                    }
                    .show()
            }
            true
        }
        pm.show()
    }

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

    private fun dayKey(t: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    private fun render(msgs: List<SmsRepo.Msg>) {
        msgBox.removeAllViews()
        val maxW = (resources.displayMetrics.widthPixels * 0.76).toInt()
        var lastDay = -1
        for ((i, m) in msgs.withIndex()) {
            val dk = dayKey(m.date)
            if (dk != lastDay) {
                lastDay = dk
                msgBox.addView(text(dayLabel(m.date), 13f, TEXT2).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(14), 0, dp(8))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            val out = m.type != Telephony.Sms.MESSAGE_TYPE_INBOX
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (out) Gravity.END else Gravity.START
                setPadding(0, dp(2), 0, dp(2))
            }
            wrap.addView(TextView(this).apply {
                text = m.body
                textSize = 16f
                maxWidth = maxW
                setTextIsSelectable(true)
                setTextColor(if (out) Color.WHITE else TEXT)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = rounded(if (out) PRIMARY else BUBBLE_IN, 20)
            })
            val isLast = i == msgs.size - 1 || msgs[i + 1].type != m.type
            val status = when (m.type) {
                Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> "Đang gửi…"
                Telephony.Sms.MESSAGE_TYPE_FAILED -> "Không gửi được · Chạm để thử lại"
                else -> if (isLast) fmtTime(m.date).let { if (it.contains(":")) it else it } else ""
            }
            if (status.isNotEmpty()) {
                wrap.addView(text(status, 12f, if (m.type == Telephony.Sms.MESSAGE_TYPE_FAILED) 0xFFB3261E.toInt() else TEXT2).apply {
                    setPadding(dp(8), dp(3), dp(8), dp(4))
                    if (m.type == Telephony.Sms.MESSAGE_TYPE_FAILED) setOnClickListener { resend(m) }
                })
            }
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
                    buildHeader()
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
