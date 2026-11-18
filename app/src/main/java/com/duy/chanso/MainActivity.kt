package com.duy.chanso

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Màn hình chính: danh sách cuộc trò chuyện */
class MainActivity : BaseActivity() {

    private data class Row(val t: SmsRepo.ThreadInfo, val name: String)

    private val rows = ArrayList<Row>()
    private lateinit var adapter: ThreadAdapter
    private lateinit var banner: LinearLayout
    private lateinit var emptyView: TextView
    private var asked = false
    private var observing = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { load() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(topBar("Tin nhắn", false,
            "✎ Soạn" to { startActivity(Intent(this, ConversationActivity::class.java)) },
            "⚙" to { startActivity(Intent(this, SettingsActivity::class.java)) }
        ))

        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFFE8F0FE.toInt())
            addView(TextView(context).apply {
                text = "App chưa là app Tin nhắn mặc định nên chưa nhận, gửi và chặn tin được."
                textSize = 14f
                setTextColor(Color.BLACK)
            })
            addView(Button(context).apply {
                text = "Đặt làm app Tin nhắn mặc định"
                isAllCaps = false
                setOnClickListener { requestSmsRole() }
            })
        }
        root.addView(banner)

        emptyView = TextView(this).apply {
            text = "Chưa có tin nhắn"
            textSize = 15f
            setTextColor(GRAY_TEXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, 0)
            visibility = View.GONE
        }
        root.addView(emptyView)

        val list = ListView(this).apply {
            divider = null
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        adapter = ThreadAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val r = rows[pos]
            startActivity(ConversationActivity.open(this, r.t.id, r.t.address))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(rows[pos]); true
        }
        root.addView(list)

        setContentView(root)
        applyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        val def = isDefaultSms()
        banner.visibility = if (def) View.GONE else View.VISIBLE
        if (def) {
            askPermissionsOnce()
            if (!observing) {
                contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
                observing = true
            }
            ContactChecker.clearCache()
        }
        load()
    }

    override fun onPause() {
        super.onPause()
        if (observing) { contentResolver.unregisterContentObserver(observer); observing = false }
    }

    private fun askPermissionsOnce() {
        if (asked) return
        asked = true
        val need = mutableListOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ContactChecker.clearCache()
        load()
    }

    private fun load() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        Thread {
            val data = try {
                SmsRepo.threads(this).map { t ->
                    Row(t, ContactChecker.displayName(this, t.address) ?: t.address.ifBlank { "(Không rõ)" })
                }
            } catch (e: Exception) { emptyList() }
            runOnUiThread {
                rows.clear(); rows.addAll(data)
                adapter.notifyDataSetChanged()
                emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun confirmDelete(r: Row) {
        if (!isDefaultSms()) { toast("Cần đặt làm app Tin nhắn mặc định trước"); return }
        AlertDialog.Builder(this)
            .setTitle("Xoá cuộc trò chuyện?")
            .setMessage("Xoá toàn bộ tin nhắn với ${r.name}.")
            .setNegativeButton("Huỷ", null)
            .setPositiveButton("Xoá") { _, _ ->
                Thread { SmsRepo.deleteThread(this, r.t.id); runOnUiThread { load() } }.start()
            }
            .show()
    }

    private inner class ThreadAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = rows[position].t.id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val ctx = this@MainActivity
            val r = rows[position]
            val unread = r.t.unread > 0

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            row.addView(avatar(r.name))

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val line1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            line1.addView(TextView(ctx).apply {
                text = r.name
                textSize = 16f
                setTextColor(Color.BLACK)
                if (unread) setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            line1.addView(TextView(ctx).apply {
                text = fmtTime(r.t.date)
                textSize = 12f
                setTextColor(if (unread) PRIMARY else GRAY_TEXT)
                setPadding(dp(8), 0, 0, 0)
            })
            col.addView(line1)

            val prefix = if (r.t.lastType != Telephony.Sms.MESSAGE_TYPE_INBOX) "Bạn: " else ""
            col.addView(TextView(ctx).apply {
                text = prefix + r.t.snippet.replace('\n', ' ')
                textSize = 14f
                setTextColor(if (unread) Color.BLACK else GRAY_TEXT)
                if (unread) setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(col)

            if (unread) {
                row.addView(TextView(ctx).apply {
                    text = r.t.unread.toString()
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    minWidth = dp(22)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(11).toFloat(); setColor(PRIMARY)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = dp(8) }
                })
            }
            return row
        }
    }
}
