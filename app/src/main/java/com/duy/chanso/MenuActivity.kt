package com.duy.chanso

import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Menu mở từ nút tròn góc phải (các nhóm bo góc) */
class MenuActivity : BaseActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(8), dp(4), dp(8), 0)
            addView(icon(R.drawable.ic_close) { finish() })
        })

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        root.addView(ScrollView(this).apply {
            addView(content)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        setContentView(root)
        applyInsets(root, BG_TOP)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        content.removeAllViews()
        val p = Prefs(this)
        val callRole = getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        val smsOn = isDefaultSms() && p.blockSms
        val callOn = callRole && p.blockCalls

        // Thẻ trạng thái
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, 32)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MenuActivity, SettingsActivity::class.java)) }
        }
        status.addView(FrameLayout(this).apply {
            background = circle(if (smsOn && callOn) 0xFF1E7F4F.toInt() else SLATE)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_shield)
                setColorFilter(Color.WHITE)
                layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
            })
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        status.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
            addView(text("Chặn số lạ", 22f))
            addView(text("Cuộc gọi: " + if (callOn) "đang chặn" else "đang tắt", 15f, TEXT2).apply { setPadding(0, dp(2), 0, 0) })
            addView(text("Tin nhắn: " + if (smsOn) "đang chặn" else "đang tắt", 15f, TEXT2))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(status, lp(12))

        content.addView(group(
            Triple(R.drawable.ic_shield, "Tin nhắn rác và bị chặn") {
                startActivity(Intent(this, BlockedActivity::class.java))
            },
            Triple(R.drawable.ic_read, "Đánh dấu tất cả là đã đọc") {
                if (!isDefaultSms()) toast("Cần đặt làm app Tin nhắn mặc định trước")
                else Thread {
                    SmsRepo.markAllRead(this)
                    runOnUiThread { toast("Đã đánh dấu tất cả là đã đọc"); finish() }
                }.start()
            }
        ), lp(12))

        val items = mutableListOf<Triple<Int, String, () -> Unit>>(
            Triple(R.drawable.ic_tune, "Cài đặt chặn số lạ") {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        )
        if (!isDefaultSms()) items.add(Triple(R.drawable.ic_app_settings, "Đặt làm ứng dụng Tin nhắn mặc định") { requestSmsRole() })
        content.addView(group(*items.toTypedArray()), lp(12))
    }

    private fun lp(bottomDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottomDp) }

    /** Nhóm các mục, bo góc lớn, ngăn cách bởi khe mỏng */
    private fun group(vararg items: Triple<Int, String, () -> Unit>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(BG_TOP, 32)
        clipToOutline = true
        items.forEachIndexed { i, (res, label, fn) ->
            if (i > 0) addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(CARD)
                setPadding(dp(14), 0, dp(16), 0)
                isClickable = true
                setOnClickListener { fn() }
                addView(icon(res, TEXT, 24, 10))
                addView(text(label, 17f).apply { setPadding(dp(10), 0, 0, 0) })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(66)))
        }
    }
}
