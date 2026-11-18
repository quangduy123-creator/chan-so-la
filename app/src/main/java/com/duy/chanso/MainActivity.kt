package com.duy.chanso

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Màn hình duy nhất: thiết lập & tuỳ chọn */
class MainActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var defaultSmsBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(topBar("Chặn Số Lạ", false))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        outer.addView(ScrollView(this).apply {
            addView(root)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        // Cảnh báo nếu app này vẫn đang là app SMS mặc định (từ bản cũ)
        defaultSmsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFFDECEA.toInt(), 20)
            setPadding(dp(16), dp(14), dp(16), dp(10))
            addView(text("Hãy chọn lại Google Tin nhắn làm app SMS mặc định", 15f, 0xFF8C1D18.toInt(), true))
            addView(button("Mở cài đặt ứng dụng mặc định") {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            })
        }
        root.addView(defaultSmsBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        root.addView(header("1. Thiết lập (làm 1 lần)"))
        statusView = TextView(this).apply {
            textSize = 14f; setTextColor(TEXT); setPadding(0, dp(4), 0, dp(8)); setLineSpacing(0f, 1.25f)
        }
        root.addView(statusView)
        root.addView(button("① Cấp quyền Danh bạ, Nhật ký cuộc gọi, Thông báo") { requestBasicPermissions() })
        root.addView(button("② Đặt làm app chặn cuộc gọi") { requestCallScreeningRole() })
        root.addView(button("③ Bật quyền đọc thông báo") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("④ Để thông báo Google Tin nhắn ở chế độ Im lặng") { openGmNotificationSettings() })
        root.addView(note("Chọn mục thông báo tin nhắn đến → \"Im lặng\". KHÔNG tắt hẳn. Tin từ danh bạ sẽ do app này báo chuông, tin số lạ sẽ không kêu."))
        root.addView(button("⑤ Bật Trợ năng \"Chặn Số Lạ\" (tự xoá tin lạ)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(note("Vào Ứng dụng đã cài đặt → Chặn Số Lạ → Bật. Nếu bị khoá: Cài đặt > Ứng dụng > Chặn Số Lạ > ⋮ > Cho phép cài đặt bị hạn chế."))

        root.addView(header("2. Chặn"))
        root.addView(toggle("Chặn cuộc gọi không có trong danh bạ", prefs.blockCalls) { prefs.blockCalls = it })
        root.addView(toggle("Ẩn thông báo tin nhắn từ số lạ", prefs.blockSms) { prefs.blockSms = it })
        root.addView(toggle("Tự xoá tin số lạ khi mở Google Tin nhắn", prefs.autoClean) { prefs.autoClean = it })
        root.addView(toggle("Dọn ngay khi mở khoá máy (chỉ khi có tin lạ mới)", prefs.cleanOnUnlock) { prefs.cleanOnUnlock = it })
        root.addView(note("Tin từ brandname (VCB, GHN, Shopee…) và đầu số dịch vụ (9029, 1900xxxx…) luôn được giữ."))

        root.addView(header("3. Danh sách cho phép"))
        root.addView(note("Số chứa các số dưới đây sẽ KHÔNG bị chặn (cả gọi lẫn tin nhắn). Cách nhau bằng dấu phẩy."))
        val wl = EditText(this).apply { setText(prefs.whitelistRaw); hint = "VD: 1900, 1800"; minLines = 2 }
        root.addView(wl)
        root.addView(button("Lưu danh sách cho phép") { prefs.whitelistRaw = wl.text.toString(); toast("Đã lưu") })

        root.addView(button("Gửi log lỗi tự xoá (để sửa app)") { shareDump() })

        root.addView(header("4. Dọn lịch sử cuộc gọi"))
        root.addView(button("Xoá lịch sử cuộc gọi cũ từ số lạ") { confirmPurgeCalls() })

        setContentView(outer)
        applyInsets(outer)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val contacts = granted(Manifest.permission.READ_CONTACTS)
        val notif = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        val role = getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        val listener = getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(ComponentName(this, SmsFilterService::class.java)) == true
        val acc = isAccessibilityOn()
        val silent = when (prefs.smsChannelSilent) {
            1 -> "✅ Google Tin nhắn đang Im lặng"
            0 -> "❌ Google Tin nhắn vẫn đang kêu (tin lạ có thể kêu 1 tiếng)"
            else -> "⏳ Google Tin nhắn: chưa xác định (cập nhật khi có tin mới)"
        }
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        statusView.text = buildString {
            appendLine("${mark(contacts)} Quyền đọc danh bạ")
            appendLine("${mark(notif)} Quyền gửi thông báo")
            appendLine("${mark(role)} App chặn cuộc gọi")
            appendLine("${mark(listener)} Quyền đọc thông báo")
            appendLine(silent)
            appendLine("${mark(acc)} Trợ năng tự xoá tin lạ")
            append("${mark(CallLogCleaner.canWrite(this@MainActivity))} Quyền nhật ký cuộc gọi (tuỳ chọn)")
        }
        defaultSmsBox.visibility =
            if (Telephony.Sms.getDefaultSmsPackage(this) == packageName) View.VISIBLE else View.GONE
    }

    private fun isAccessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, CleanerService::class.java)
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it)?.let { c -> c.packageName == me.packageName && c.className == me.className } == true
        }
    }

    private fun openGmNotificationSettings() {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, CleanerService.GM)
        try { startActivity(i) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun requestBasicPermissions() {
        val perms = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun requestCallScreeningRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) { toast("Máy không hỗ trợ vai trò chặn cuộc gọi"); return }
        if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) { toast("Đã là app chặn cuộc gọi"); return }
        @Suppress("DEPRECATION")
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), REQ_ROLE_CALL)
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

    private fun shareDump() {
        val f = java.io.File(filesDir, "last_dump.txt")
        if (!f.exists()) { toast("Chưa có log lỗi"); return }
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, f.readText().take(60000))
        startActivity(Intent.createChooser(i, "Gửi log"))
    }

    private fun confirmPurgeCalls() {
        if (!CallLogCleaner.canWrite(this)) { toast("Cần cấp quyền Nhật ký cuộc gọi trước"); return }
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

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 17f; setTextColor(TEXT); setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(GRAY_TEXT); setPadding(0, dp(2), 0, dp(6))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
    }

    private fun toggle(t: String, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = t; isChecked = initial; textSize = 15f; setTextColor(Color.BLACK)
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }
}
