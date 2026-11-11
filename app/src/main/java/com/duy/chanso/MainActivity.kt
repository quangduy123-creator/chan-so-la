package com.duy.chanso

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(header("1. Thiết lập (làm 1 lần)"))
        statusView = TextView(this).apply { textSize = 14f; setPadding(0, dp(4), 0, dp(8)) }
        root.addView(statusView)
        root.addView(button("Cấp quyền Danh bạ & Nhật ký cuộc gọi") {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG),
                1
            )
        })
        root.addView(button("Đặt làm app chặn cuộc gọi") { requestCallScreeningRole() })
        root.addView(button("Bật quyền đọc thông báo (để ẩn SMS)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        root.addView(header("2. Tuỳ chọn"))
        root.addView(toggle("Chặn cuộc gọi không có trong danh bạ", prefs.blockCalls) { prefs.blockCalls = it })
        root.addView(toggle("Ẩn tin nhắn không có trong danh bạ", prefs.hideSms) { prefs.hideSms = it })
        root.addView(toggle("Tự đánh dấu tin lạ là đã đọc", prefs.markRead) { prefs.markRead = it })
        root.addView(toggle("Thử xoá tin lạ (chỉ khi app SMS có nút Xoá trên thông báo)", prefs.tryDelete) { prefs.tryDelete = it })

        root.addView(header("3. Danh sách cho phép"))
        root.addView(note("Số hoặc tên người gửi chứa từ khoá dưới đây sẽ KHÔNG bị chặn (ngân hàng gửi OTP, Grab, GHN...). Cách nhau bằng dấu phẩy."))
        val wl = EditText(this).apply {
            setText(prefs.whitelistRaw)
            hint = "VD: Vietcombank, Techcombank, Grab, 1900"
            minLines = 2
        }
        root.addView(wl)
        root.addView(button("Lưu danh sách cho phép") {
            prefs.whitelistRaw = wl.text.toString()
            toast("Đã lưu")
        })

        root.addView(header("4. Dọn lịch sử cũ"))
        root.addView(button("Xoá lịch sử cuộc gọi cũ từ số lạ") { confirmPurge() })

        root.addView(header("5. Nhật ký đã chặn"))
        root.addView(button("Xoá nhật ký") { BlockLog.clear(this); refreshLog() })
        logView = TextView(this).apply { setTextIsSelectable(true); textSize = 13f }
        root.addView(logView)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshLog()
    }

    // ---------- Trạng thái ----------

    private fun refreshStatus() {
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val callLog = CallLogCleaner.canWrite(this)
        val role = getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        val listener = getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(ComponentName(this, SmsFilterService::class.java)) == true

        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        statusView.text = buildString {
            appendLine("${mark(contacts)} Quyền đọc danh bạ (bắt buộc)")
            appendLine("${mark(role)} App chặn cuộc gọi (bắt buộc cho chặn gọi)")
            appendLine("${mark(listener)} Quyền đọc thông báo (bắt buộc cho ẩn SMS)")
            append("${mark(callLog)} Quyền nhật ký cuộc gọi (tuỳ chọn – dọn lịch sử)")
        }
    }

    private fun requestCallScreeningRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            toast("Máy không hỗ trợ vai trò chặn cuộc gọi")
            return
        }
        if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            toast("Đã là app chặn cuộc gọi")
            return
        }
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 2)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
        if (!CallLogCleaner.canWrite(this)) {
            toast("Nếu quyền Nhật ký cuộc gọi bị khoá: Cài đặt > Ứng dụng > Chặn Số Lạ > ⋮ > Cho phép cài đặt bị hạn chế")
        }
    }

    // ---------- Dọn lịch sử ----------

    private fun confirmPurge() {
        if (!CallLogCleaner.canWrite(this)) {
            toast("Cần cấp quyền Nhật ký cuộc gọi trước")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Xoá lịch sử cuộc gọi từ số lạ?")
            .setMessage("Mọi cuộc gọi cũ từ số không có trong danh bạ sẽ bị xoá khỏi lịch sử. Không hoàn tác được.")
            .setNegativeButton("Huỷ", null)
            .setPositiveButton("Xoá") { _, _ ->
                Thread {
                    val n = try { CallLogCleaner.purgeUnknown(this) } catch (e: Exception) { -1 }
                    runOnUiThread { toast(if (n >= 0) "Đã xoá $n mục" else "Không xoá được (thiếu quyền)") }
                }.start()
            }
            .show()
    }

    // ---------- Nhật ký ----------

    private fun refreshLog() {
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        val items = BlockLog.all(this)
        logView.text = if (items.isEmpty()) "Chưa có gì." else items.joinToString("\n\n") { o ->
            val icon = if (o.optString("type") == "CALL") "📞" else "✉️"
            "$icon ${fmt.format(Date(o.optLong("t")))} – ${o.optString("who")}\n${o.optString("detail")}"
        }
    }

    // ---------- UI helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun note(t: String) = TextView(this).apply { text = t; textSize = 13f }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false
        setOnClickListener(View.OnClickListener { onClick() })
    }

    private fun toggle(t: String, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = t; isChecked = initial; textSize = 15f
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
