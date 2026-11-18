package com.duy.chanso

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Màn hình chính: danh sách cuộc trò chuyện */
class MainActivity : BaseActivity() {

    private data class Row(val t: SmsRepo.ThreadInfo, val name: String, val contact: Boolean)

    private val rows = ArrayList<Row>()
    private lateinit var adapter: ThreadAdapter
    private lateinit var header: LinearLayout
    private lateinit var banner: LinearLayout
    private lateinit var miniBar: LinearLayout
    private lateinit var footerText: TextView
    private var asked = false
    private var observing = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { load() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)

        val list = ListView(this).apply {
            divider = null
            setBackgroundColor(SHEET)
            isVerticalScrollBarEnabled = false
        }
        header = buildHeader()
        list.addHeaderView(header, null, false)
        footerText = text("", 15f, TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(120))
        }
        list.addFooterView(footerText, null, false)
        adapter = ThreadAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val r = list.getItemAtPosition(pos) as? Row ?: return@setOnItemClickListener
            startActivity(ConversationActivity.open(this, r.t.id, r.t.address))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val r = list.getItemAtPosition(pos) as? Row
            if (r != null) threadOptions(r)
            true
        }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                val collapsed = first > 0 || header.top < -(header.height - dp(110))
                miniBar.visibility = if (collapsed) View.VISIBLE else View.GONE
            }
        })
        root.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Thanh nhỏ hiện khi cuộn xuống
        miniBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG_TOP)
            setPadding(dp(20), dp(4), dp(12), dp(4))
            visibility = View.GONE
            addView(text("Tin nhắn", 22f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(icon(R.drawable.ic_search) { openSearch() })
            addView(accountButton())
        }
        root.addView(miniBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Nút soạn tin (góc dưới phải)
        val fab = FrameLayout(this).apply {
            background = rounded(FAB, 18)
            elevation = dp(3).toFloat()
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, ConversationActivity::class.java)) }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chat)
                setColorFilter(0xFF1D2A4D.toInt())
                layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
            })
        }
        root.addView(fab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(20), dp(24))
        })

        setContentView(root)
        applyInsets(root, BG_TOP)
    }

    private fun buildHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG_TOP)

        val titleH = (resources.displayMetrics.heightPixels * 0.24).toInt()
        addView(FrameLayout(context).apply {
            addView(text("Tin nhắn", 34f).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, titleH))

        banner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 24)
            setPadding(dp(20), dp(16), dp(20), dp(12))
            addView(text("Chưa là app Tin nhắn mặc định", 16f, TEXT, true))
            addView(text("Cần đặt mặc định để nhận, gửi và chặn tin nhắn.", 14f, TEXT2).apply {
                setPadding(0, dp(4), 0, dp(8))
            })
            addView(Button(context).apply {
                text = "Đặt mặc định"
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = rounded(PRIMARY, 20)
                setOnClickListener { requestSmsRole() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        }
        addView(banner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), 0, dp(16), dp(12))
        })

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(14), dp(8))
            addView(icon(R.drawable.ic_search) { openSearch() })
            addView(accountButton())
        })

        // Mép trên bo tròn của khung danh sách
        addView(View(context).apply { background = topRounded(SHEET, 28) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))
    }

    /** Nút tròn góc phải (mở menu) */
    private fun accountButton(): View = FrameLayout(this).apply {
        background = circle(SLATE)
        isClickable = true
        setOnClickListener { startActivity(Intent(this@MainActivity, MenuActivity::class.java)) }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_shield)
            setColorFilter(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        })
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(6) }
    }

    private fun openSearch() = startActivity(Intent(this, SearchActivity::class.java))

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
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            footerText.text = ""
            return
        }
        Thread {
            val data = try {
                SmsRepo.threads(this).map { t ->
                    val n = ContactChecker.displayName(this, t.address)
                    Row(t, n ?: t.address.ifBlank { "(Không rõ)" }, n != null)
                }
            } catch (e: Exception) { emptyList() }
            runOnUiThread {
                rows.clear(); rows.addAll(data)
                adapter.notifyDataSetChanged()
                footerText.text = if (rows.isEmpty()) "Chưa có cuộc trò chuyện nào" else ""
            }
        }.start()
    }

    private fun threadOptions(r: Row) {
        AlertDialog.Builder(this)
            .setTitle(r.name)
            .setItems(arrayOf("Đánh dấu đã đọc", "Xoá cuộc trò chuyện")) { _, which ->
                if (!isDefaultSms()) { toast("Cần đặt làm app Tin nhắn mặc định trước"); return@setItems }
                when (which) {
                    0 -> Thread { SmsRepo.markRead(this, r.t.id); Notifier.cancel(this, r.t.id) }.start()
                    1 -> confirmDelete(r)
                }
            }
            .show()
    }

    private fun confirmDelete(r: Row) {
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
            val r = rows[position]
            val prefix = if (r.t.lastType != Telephony.Sms.MESSAGE_TYPE_INBOX) "Bạn: " else ""
            return threadRow(r.name, prefix + r.t.snippet, fmtTime(r.t.date), r.t.unread, r.contact)
        }
    }
}
