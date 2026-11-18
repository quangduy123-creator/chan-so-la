package com.duy.chanso

import android.app.Activity
import android.app.role.RoleManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Telephony
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

abstract class BaseActivity : Activity() {

    companion object {
        const val PRIMARY = 0xFF1A73E8.toInt()
        const val GRAY_BG = 0xFFECEFF1.toInt()
        const val GRAY_TEXT = 0xFF6B7280.toInt()
        const val REQ_ROLE_SMS = 101
        const val REQ_ROLE_CALL = 102

        private val AVATAR_COLORS = intArrayOf(
            0xFF1A73E8.toInt(), 0xFF188038.toInt(), 0xFFD93025.toInt(), 0xFFF29900.toInt(),
            0xFF9334E6.toInt(), 0xFF00897B.toInt(), 0xFFE52592.toInt(), 0xFF5F6368.toInt()
        )

        fun fmtTime(t: Long): String {
            val now = Calendar.getInstance()
            val c = Calendar.getInstance().apply { timeInMillis = t }
            val pattern = when {
                now.get(Calendar.YEAR) == c.get(Calendar.YEAR) &&
                    now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR) -> "HH:mm"
                now.get(Calendar.YEAR) == c.get(Calendar.YEAR) -> "dd/MM"
                else -> "dd/MM/yy"
            }
            return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(t))
        }
    }

    var titleView: TextView? = null

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun isDefaultSms() = Telephony.Sms.getDefaultSmsPackage(this) == packageName

    fun requestSmsRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
            toast("Máy không hỗ trợ đổi app Tin nhắn mặc định")
            return
        }
        if (rm.isRoleHeld(RoleManager.ROLE_SMS)) { toast("Đã là app Tin nhắn mặc định"); return }
        @Suppress("DEPRECATION")
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), REQ_ROLE_SMS)
    }

    fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    /** Chừa chỗ cho thanh trạng thái, thanh điều hướng và bàn phím (Android 15 tràn viền) */
    @Suppress("DEPRECATION")
    fun applyInsets(v: View) {
        v.setOnApplyWindowInsetsListener { view, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                top = bars.top
                bottom = maxOf(bars.bottom, ime.bottom)
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(0, top, 0, bottom)
            insets
        }
        v.setBackgroundColor(Color.WHITE)
        if (Build.VERSION.SDK_INT >= 30) {
            val f = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(f, f)
        } else {
            window.statusBarColor = Color.WHITE
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    fun topBar(title: String, back: Boolean, vararg actions: Pair<String, () -> Unit>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            if (back) addView(textButton("←") { finish() })
            val t = TextView(context).apply {
                text = title
                textSize = 21f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.BLACK)
                setPadding(dp(12), 0, dp(4), 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleView = t
            addView(t)
            actions.forEach { (label, fn) -> addView(textButton(label, fn)) }
        }

    fun textButton(label: String, fn: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 17f
        setTextColor(PRIMARY)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { fn() }
    }

    fun avatar(name: String, sizeDp: Int = 44): TextView = TextView(this).apply {
        val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
        text = letter
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(AVATAR_COLORS[(name.hashCode() and 0x7fffffff) % AVATAR_COLORS.size])
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }
}
