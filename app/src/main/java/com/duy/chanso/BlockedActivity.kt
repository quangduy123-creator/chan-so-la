package com.duy.chanso

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tin nhắn rác và bị chặn */
class BlockedActivity : BaseActivity() {

    private val items = ArrayList<BlockedStore.Item>()
    private lateinit var adapter: ItemAdapter
    private lateinit var list: ListView
    private lateinit var empty: LinearLayout
    private lateinit var emptyNote: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = topBar("Tin nhắn rác và bị chặn", true)
        lateinit var more: View
        more = icon(R.drawable.ic_more) { showMenu(more) }
        bar.addView(more)
        root.addView(bar)

        val sheet = FrameLayout(this).apply { background = topRounded(SHEET, 28) }
        list = ListView(this).apply { divider = null; setPadding(0, dp(8), 0, dp(8)); clipToPadding = false }
        adapter = ItemAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> openItem(items[pos]) }
        sheet.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), dp(48))
            addView(ImageView(context).apply { setImageResource(R.drawable.ill_blocked) },
                LinearLayout.LayoutParams(dp(220), dp(184)))
            addView(text("Các tin nhắn rác và cuộc trò chuyện bị chặn sẽ xuất hiện tại đây", 17f, TEXT2).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, 0)
            })
        }
        emptyNote = text("", 13f, GRAY_TEXT).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        empty.addView(emptyNote)
        sheet.addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        root.addView(sheet, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        applyInsets(root, BG_TOP)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        items.clear()
        items.addAll(BlockedStore.all(this))
        adapter.notifyDataSetChanged()
        val isEmpty = items.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyNote.text = if (Prefs(this).keepBlocked) "Tin bị chặn được giữ 7 ngày rồi tự xoá."
        else "Đang ở chế độ xoá ngay. Bật \"Giữ tin bị chặn 7 ngày\" trong Cài đặt để xem lại tin tại đây."
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Xoá tất cả")
        pm.menu.add(0, 2, 1, "Cài đặt chặn số lạ")
        pm.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> AlertDialog.Builder(this)
                    .setTitle("Xoá tất cả tin bị chặn?")
                    .setNegativeButton("Huỷ", null)
                    .setPositiveButton("Xoá") { _, _ -> BlockedStore.clear(this); load() }
                    .show()
                2 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        pm.show()
    }

    private fun openItem(it: BlockedStore.Item) {
        val time = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date(it.time))
        AlertDialog.Builder(this)
            .setTitle(it.address)
            .setMessage(it.body + "\n\n" + time)
            .setNeutralButton("Đóng", null)
            .setNegativeButton("Xoá") { _, _ -> BlockedStore.remove(this, it); load() }
            .setPositiveButton("Khôi phục") { _, _ ->
                if (!isDefaultSms()) { toast("Cần đặt làm app Tin nhắn mặc định trước"); return@setPositiveButton }
                Thread {
                    SmsRepo.insertIncoming(this, it.address, it.body, it.time, it.subId)
                    BlockedStore.remove(this, it)
                    runOnUiThread { toast("Đã khôi phục vào hộp thư"); load() }
                }.start()
            }
            .show()
    }

    private inner class ItemAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val i = items[position]
            return threadRow(i.address, i.body, fmtTime(i.time), 0, false)
        }
    }
}
