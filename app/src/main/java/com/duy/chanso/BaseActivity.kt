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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Nền tảng giao diện chung (phong cách Material 3) */
abstract class BaseActivity : Activity() {

    companion object {
        const val BG_TOP = 0xFFECEDF4.toInt()      // nền phía trên
        const val SHEET = 0xFFF7F7FC.toInt()       // khung danh sách bo góc
        const val CARD = 0xFFFFFFFF.toInt()
        const val TEXT = 0xFF1B1B1F.toInt()
        const val TEXT2 = 0xFF46464F.toInt()
        const val PRIMARY = 0xFF0B57D0.toInt()
        const val BADGE = 0xFF44578F.toInt()
        const val FAB = 0xFFD8E2FF.toInt()
        const val BUBBLE_IN = 0xFFE7E8EF.toInt()
        const val SLATE = 0xFF455A64.toInt()
        const val GRAY_BG = 0xFFE7E8EF.toInt()
        const val GRAY_TEXT = 0xFF6B6D78.toInt()
        const val REQ_ROLE_SMS = 101
        const val REQ_ROLE_CALL = 102

        private val AVATAR_COLORS = intArrayOf(
            0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(),
            0xFF8E24AA.toInt(), 0xFF00897B.toInt(), 0xFFD81B60.toInt(), 0xFF5E35B1.toInt()
        )

        fun fmtTime(t: Long): String {
            val now = Calendar.getInstance()
            val c = Calendar.getInstance().apply { timeInMillis = t }
            val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
            val dayDiff = now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
            return when {
                sameYear && dayDiff == 0 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
                sameYear && dayDiff in 1..6 -> arrayOf("CN", "Th 2", "Th 3", "Th 4", "Th 5", "Th 6", "Th 7")[c.get(Calendar.DAY_OF_WEEK) - 1]
                sameYear -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(t))
                else -> SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(t))
            }
        }

        fun dayLabel(t: Long): String {
            val now = Calendar.getInstance()
            val c = Calendar.getInstance().apply { timeInMillis = t }
            val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
            val diff = now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
            return when {
                sameYear && diff == 0 -> "Hôm nay"
                sameYear && diff == 1 -> "Hôm qua"
                else -> SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("vi")).format(Date(t))
            }
        }
    }

    var titleView: TextView? = null

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun isDefaultSms() = Telephony.Sms.getDefaultSmsPackage(this) == packageName

    fun requestSmsRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
            toast("Máy không hỗ trợ đổi app Tin nhắn mặc định"); return
        }
        if (rm.isRoleHeld(RoleManager.ROLE_SMS)) { toast("Đã là app Tin nhắn mặc định"); return }
        @Suppress("DEPRECATION")
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), REQ_ROLE_SMS)
    }

    fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    /** Chừa chỗ cho thanh trạng thái, thanh điều hướng và bàn phím (Android 15 tràn viền) */
    @Suppress("DEPRECATION")
    fun applyInsets(v: View, bg: Int = SHEET) {
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
        v.setBackgroundColor(bg)
        if (Build.VERSION.SDK_INT >= 30) {
            val f = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(f, f)
        } else {
            window.statusBarColor = bg
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    // ---------- Khối dựng giao diện ----------

    fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    fun topRounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        val r = dp(radiusDp).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    fun icon(res: Int, color: Int = TEXT, sizeDp: Int = 24, padDp: Int = 12, onClick: (() -> Unit)? = null) =
        ImageView(this).apply {
            setImageResource(res)
            setColorFilter(color)
            val p = dp(padDp)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp) + 2 * p, dp(sizeDp) + 2 * p)
            if (onClick != null) {
                isClickable = true
                background = circle(Color.TRANSPARENT)
                setOnClickListener { onClick() }
            }
        }

    fun text(t: String, sizeSp: Float, color: Int = TEXT, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sizeSp; setTextColor(color)
        if (bold) setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
    }

    /** Thanh tiêu đề: ← Tiêu đề …  (nhãn chữ bên phải là nút) */
    fun topBar(title: String, back: Boolean, vararg actions: Pair<String, () -> Unit>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            if (back) addView(icon(R.drawable.ic_back) { finish() })
            val t = TextView(context).apply {
                text = title
                textSize = 22f
                setTextColor(TEXT)
                setPadding(dp(if (back) 4 else 16), 0, dp(4), 0)
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
        textSize = 16f
        setTextColor(PRIMARY)
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { fn() }
    }

    /** Ảnh đại diện: người trong danh bạ = vòng màu có chữ cái; số lạ/brandname = hình người xám */
    fun avatar(name: String, isContact: Boolean, sizeDp: Int = 52): View {
        if (!isContact) {
            return FrameLayout(this).apply {
                background = circle(0xFFE3E4EA.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_person)
                    setColorFilter(0xFFA3A6B2.toInt())
                    layoutParams = FrameLayout.LayoutParams(dp(sizeDp / 2), dp(sizeDp / 2), Gravity.CENTER)
                })
            }
        }
        return TextView(this).apply {
            val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
            text = letter
            textSize = (sizeDp * 0.38f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = circle(AVATAR_COLORS[(name.hashCode() and 0x7fffffff) % AVATAR_COLORS.size])
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
    }

    /** Một dòng trong danh sách cuộc trò chuyện (dùng chung cho danh sách, tìm kiếm, tin bị chặn) */
    fun threadRow(name: String, snippet: String, time: String, unread: Int, isContact: Boolean, bg: Int = SHEET): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(bg)
        }
        row.addView(avatar(name, isContact))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(text(name, 17f, TEXT, unread > 0).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        col.addView(text(snippet.replace('\n', ' '), 15f, if (unread > 0) TEXT else TEXT2, unread > 0).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.TOP
        }
        right.addView(text(time, 13f, if (unread > 0) TEXT else TEXT2, unread > 0))
        if (unread > 0) {
            right.addView(TextView(this).apply {
                text = if (unread > 99) "99+" else unread.toString()
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                minWidth = dp(22); minHeight = dp(22)
                setPadding(dp(6), 0, dp(6), 0)
                background = rounded(BADGE, 11)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6); gravity = Gravity.END }
            })
        }
        row.addView(right)
        return row
    }
}
