package com.duy.chanso

import android.Manifest
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Cài đặt: quyền, chặn cuộc gọi, chặn tin nhắn, danh sách cho phép, dọn dẹp */
class SettingsActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(topBar("Cài đặt", true))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        outer.addView(ScrollView(this).apply {
            addView(root)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        root.addView(header("1. Thiết lập (làm 1 lần)"))
        statusView = TextView(this).apply {
            textSize = 14f; setTextColor(Color.BLACK); setPadding(0, dp(4), 0, dp(8)); setLineSpacing(0f, 1.2f)
        }
        root.addView(statusView)
        root.addView(button("Đặt làm app Tin nhắn mặc định") { requestSmsRole() })
        root.addView(button("Đặt làm app chặn cuộc gọi") { requestCallScreeningRole() })
        root.addView(button("Cấp quyền Danh bạ, Nhật ký cuộc gọi, Thông báo") { requestBasicPermissions() })

        root.addView(header("2. Chặn"))
        root.addView(toggle("Chặn cuộc gọi không có trong danh bạ", prefs.blockCalls) { prefs.blockCalls = it })
        root.addView(toggle("Chặn & xoá tin nhắn từ số lạ", prefs.blockSms) { prefs.blockSms = it })
        root.addView(note("Tin từ brandname (VCB, GHN, Shopee…) và đầu số dịch vụ (9029, 1900xxxx…) luôn được nhận."))

        root.addView(header("3. Danh sách cho phép"))
        root.addView(note("Số chứa các số dưới đây sẽ KHÔNG bị chặn (cả gọi lẫn tin nhắn). Cách nhau bằng dấu phẩy."))
        val wl = EditText(this).apply {
            setText(prefs.whitelistRaw)
            hint = "VD: 1900, 1800"
            minLines = 2
        }
        root.addView(wl)
        root.addView(button("Lưu danh sách cho phép") {
            prefs.whitelistRaw = wl.text.toString()
            toast("Đã lưu")
        })

        root.addView(header("4. Dọn dẹp"))
        root.addView(button("Xoá tin nhắn cũ từ số lạ") { confirmPurgeSms() })
        root.addView(note("Chỉ xoá cuộc trò chuyện với số lạ mà bạn chưa từng nhắn đi."))
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
        val callLog = CallLogCleaner.canWrite(this)
        val notif = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        val role = getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        statusView.text = buildString {
            appendLine("${mark(isDefaultSms())} App Tin nhắn mặc định")
            appendLine("${mark(role)} App chặn cuộc gọi")
            appendLine("${mark(contacts)} Quyền đọc danh bạ")
            appendLine("${mark(notif)} Quyền thông báo")
            append("${mark(callLog)} Quyền nhật ký cuộc gọi (để dọn lịch sử)")
        }
    }

    private fun requestBasicPermissions() {
        val perms = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun requestCallScreeningRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            toast("Máy không hỗ trợ vai trò chặn cuộc gọi"); return
        }
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

    private fun confirmPurgeSms() {
        if (!isDefaultSms()) { toast("Cần đặt làm app Tin nhắn mặc định trước"); return }
        AlertDialog.Builder(this)
            .setTitle("Xoá tin nhắn cũ từ số lạ?")
            .setMessage("Xoá các cuộc trò chuyện với số không có trong danh bạ (trừ brandname, đầu số dịch vụ, danh sách cho phép và những số bạn từng nhắn đi). Không hoàn tác được.")
            .setNegativeButton("Huỷ", null)
            .setPositiveButton("Xoá") { _, _ ->
                Thread {
                    val n = try { SmsRepo.purgeSpam(this) } catch (e: Exception) { -1 }
                    runOnUiThread { toast(if (n >= 0) "Đã xoá $n cuộc trò chuyện" else "Không xoá được") }
                }.start()
            }
            .show()
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

    // ---------- UI helpers ----------

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 17f; setTextColor(Color.BLACK); setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(GRAY_TEXT); setPadding(0, dp(2), 0, dp(4))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toggle(t: String, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = t; isChecked = initial; textSize = 15f; setTextColor(Color.BLACK)
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }
}
