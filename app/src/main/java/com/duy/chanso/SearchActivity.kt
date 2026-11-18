package com.duy.chanso

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony.Sms
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView

/** Tìm theo tên/số người gửi và nội dung tin */
class SearchActivity : BaseActivity() {

    private data class Hit(val threadId: Long, val address: String, val name: String, val contact: Boolean, val text: String, val date: Long)

    private val hits = ArrayList<Hit>()
    private lateinit var adapter: HitAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var threads: List<SmsRepo.ThreadInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(12), dp(4))
        }
        bar.addView(icon(R.drawable.ic_back) { finish() })
        val input = EditText(this).apply {
            hint = "Tìm kiếm tin nhắn"
            textSize = 18f
            setTextColor(TEXT)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(input)
        root.addView(bar)

        val list = ListView(this).apply {
            divider = null
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        adapter = HitAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val h = hits[pos]
            startActivity(ConversationActivity.open(this, h.threadId, h.address))
        }
        root.addView(list)

        setContentView(root)
        applyInsets(root, SHEET)

        Thread { threads = try { SmsRepo.threads(this) } catch (e: Exception) { emptyList() } }.start()

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { handler.removeCallbacks(it) }
                val q = s?.toString()?.trim().orEmpty()
                val r = Runnable { search(q) }
                pending = r
                handler.postDelayed(r, 250)
            }
        })
        input.requestFocus()
        handler.postDelayed({
            getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun search(q: String) {
        if (q.isEmpty()) { hits.clear(); adapter.notifyDataSetChanged(); return }
        Thread {
            val out = ArrayList<Hit>()
            val seen = HashSet<Long>()
            val ql = q.lowercase()
            // 1) Theo tên / số
            for (t in threads) {
                val n = ContactChecker.displayName(this, t.address)
                val name = n ?: t.address
                if (name.lowercase().contains(ql) || t.address.contains(q)) {
                    out.add(Hit(t.id, t.address, name, n != null, t.snippet, t.date)); seen.add(t.id)
                }
            }
            // 2) Theo nội dung tin
            try {
                contentResolver.query(
                    Sms.CONTENT_URI, arrayOf(Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE),
                    "${Sms.BODY} LIKE ?", arrayOf("%$q%"), "${Sms.DATE} DESC"
                )?.use { c ->
                    var count = 0
                    while (c.moveToNext() && count < 100) {
                        val addr = c.getString(1) ?: ""
                        val n = ContactChecker.displayName(this, addr)
                        out.add(Hit(c.getLong(0), addr, n ?: addr, n != null, c.getString(2) ?: "", c.getLong(3)))
                        count++
                    }
                }
            } catch (e: Exception) { }
            runOnUiThread { hits.clear(); hits.addAll(out); adapter.notifyDataSetChanged() }
        }.start()
    }

    private inner class HitAdapter : BaseAdapter() {
        override fun getCount() = hits.size
        override fun getItem(position: Int): Any = hits[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h = hits[position]
            return threadRow(h.name, h.text, fmtTime(h.date), 0, h.contact)
        }
    }
}
