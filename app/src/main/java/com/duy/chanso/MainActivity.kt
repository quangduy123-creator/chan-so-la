package com.duy.chanso

import android.Manifest
import android.app.Activity
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
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root)
        }
        setContentView(scroll)
        applySystemBarInsets(scroll)

        root.addView(TextView(this).apply {
            text = "Chặn Số Lạ"; textSize = 24f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        })

        root.addView(header("1. Thiết lập (làm 1 lần)"))
        statusView = TextView(this).apply { textSize = 14f; setPadding(0, dp(4), 0, dp(8)); setLineSpacing(0f, 1.2f) }
        root.addView(statusView)
        root.addView(button("Cấp quyền Danh bạ, Nhật ký cuộc gọi, Thông báo") { requestBasicPermissions() })
        root.addView(button("Đặt làm app chặn cuộc gọi") { requestCallScreeningRole() })
        root.addView(button("Bật quyền đọc thông báo") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("Để thông báo app Tin nhắn ở chế độ Im lặng") { openSmsAppNotificationSettings() })
        root.addView(note("Ở màn hình mở ra: chọn mục thông báo tin nhắn đến → chọn \"Im lặng\". KHÔNG tắt hẳn thông báo. " +
            "Sau đó tin từ danh bạ sẽ do app này báo chuông, tin từ số lạ sẽ không kêu và không hiện."))

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
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------- Giao diện tràn viền (Android 15) ----------

    @Suppress("DEPRECATION")
    private fun applySystemBarInsets(v: View) {
        v.setOnApplyWindowInsetsListener { view, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = i.top; bottom = i.bottom
            } else {
                top = insets.systemWindowInsetTop; bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(0, top, 0, bottom)
            insets
        }
        // Chữ trên thanh trạng thái màu tối (nền trắng)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            window.statusBarColor = Color.WHITE
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    // ---------- Trạng thái ----------

    private fun refreshStatus() {
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val callLog = CallLogCleaner.canWrite(this)
        val notif = getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true &&
            (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val role = getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        val listener = getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(ComponentName(this, SmsFilterService::class.java)) == true
        val silent = when (prefs.smsChannelSilent) {
            1 -> "✅ App Tin nhắn đang Im lặng"
            0 -> "❌ App Tin nhắn vẫn đang kêu (tin lạ vẫn có thể kêu 1 tiếng)"
            else -> "⏳ App Tin nhắn: chưa xác định (sẽ cập nhật khi có tin mới)"
        }

        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        statusView.text = buildString {
            appendLine("${mark(contacts)} Quyền đọc danh bạ")
            appendLine("${mark(role)} App chặn cuộc gọi")
            appendLine("${mark(listener)} Quyền đọc thông báo")
            appendLine("${mark(notif)} Quyền gửi thông báo")
            appendLine("${mark(callLog)} Quyền nhật ký cuộc gọi (tuỳ chọn)")
            append(silent)
        }
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

    private fun openSmsAppNotificationSettings() {
        val pkg = Telephony.Sms.getDefaultSmsPackage(this)
        val intent = if (pkg != null) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
        try { startActivity(intent) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
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

    // ---------- UI helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun note(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(2), 0, dp(4)) }

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
