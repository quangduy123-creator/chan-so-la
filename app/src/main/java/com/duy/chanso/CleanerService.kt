package com.duy.chanso

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Tự xoá các cuộc trò chuyện có tiêu đề là SỐ LẠ trong Google Tin nhắn:
 *   giữ lâu dòng đó -> bấm thùng rác -> bấm "Chuyển vào thùng rác".
 * Chạy khi:
 *   (a) bạn mở Google Tin nhắn, hoặc
 *   (b) vừa mở khoá máy VÀ có tin lạ mới: app tự mở Google Tin nhắn, dọn xong tự về màn hình chính.
 */
class CleanerService : AccessibilityService() {

    companion object {
        const val GM = "com.google.android.apps.messaging"
        private const val MAX_PER_SESSION = 40
        private val DELETE_KEYS = listOf("xoa", "delete", "trash", "thung rac")

        /** Nút xác nhận (so khớp CHÍNH XÁC để không bấm nhầm tiêu đề / nút Huỷ) */
        private val CONFIRM_LABELS = setOf(
            "chuyen vao thung rac", "xoa", "xoa cuoc tro chuyen", "xoa vinh vien",
            "move to trash", "delete", "delete conversation"
        )

        /** Bỏ dấu + chữ thường: "Chuyển vào thùng rác" -> "chuyen vao thung rac" */
        fun plain(s: CharSequence?): String {
            if (s == null) return ""
            val n = java.text.Normalizer.normalize(s.toString(), java.text.Normalizer.Form.NFD)
            return n.replace(Regex("\\p{Mn}+"), "").replace('đ', 'd').replace('Đ', 'D')
                .lowercase().trim().trimEnd('?', '.', '!', ' ')
        }

        fun isDeleteLabel(s: CharSequence?): Boolean {
            val p = plain(s)
            return p.isNotEmpty() && p.length <= 24 && DELETE_KEYS.any { p.contains(it) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var scheduled = false
    private var deletedThisSession = 0
    private val failed = HashMap<String, Int>()
    private var inGm = false

    /** Phiên tự động sau khi mở khoá */
    private var autoSession = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_USER_PRESENT) onUnlocked()
        }
    }

    override fun onServiceConnected() {
        try { registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT)) } catch (e: Exception) { }
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlockReceiver) } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------- Mở khoá máy ----------
    private fun onUnlocked() {
        val p = Prefs(this)
        if (!p.autoClean || !p.blockSms || !p.cleanOnUnlock || !p.pendingClean) return
        val launch = packageManager.getLaunchIntentForPackage(GM) ?: return
        handler.postDelayed({
            autoSession = true
            resetSession()
            try {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            } catch (e: Exception) { autoSession = false; return@postDelayed }
            // Chốt an toàn: tối đa 25 giây
            handler.postDelayed({ if (autoSession) endAutoSession() }, 25_000)
        }, 400)
    }

    private fun endAutoSession() {
        autoSession = false
        Prefs(this).pendingClean = false
        if (inGm) performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun resetSession() {
        deletedThisSession = 0
        failed.clear()
    }

    // ---------- Sự kiện màn hình ----------
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (pkg != GM) {
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && inGm && !isSystemUi(pkg)) {
                inGm = false
                resetSession()
            }
            return
        }
        inGm = true
        val p = Prefs(this)
        if (!p.autoClean || !p.blockSms) return
        if (busy || scheduled) return
        scheduled = true
        handler.postDelayed({ scheduled = false; scan() }, 300)
    }

    private fun isSystemUi(pkg: String) = pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("honeyboard")

    private fun gmRoot(): AccessibilityNodeInfo? =
        rootInActiveWindow?.takeIf { it.packageName?.toString() == GM }
            ?: windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == GM }

    /** Tất cả cửa sổ của Google Tin nhắn (hộp thoại là một cửa sổ riêng) */
    private fun gmRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try { windows.forEach { w -> w.root?.let { if (it.packageName?.toString() == GM) out.add(it) } } } catch (e: Exception) { }
        rootInActiveWindow?.let { if (it.packageName?.toString() == GM && out.none { o -> o == it }) out.add(0, it) }
        return out
    }

    // ---------- Bước 1: tìm & giữ lâu một dòng số lạ ----------
    private fun scan() {
        if (busy) return
        if (deletedThisSession >= MAX_PER_SESSION) { if (autoSession) endAutoSession(); return }
        val r = gmRoot() ?: return
        val found = findSpamRow(r)
        if (found == null) {
            // Không còn tin lạ
            Prefs(this).pendingClean = false
            if (autoSession) handler.postDelayed({ if (autoSession) endAutoSession() }, 600)
            return
        }
        val (row, number) = found
        busy = true
        if (!row.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            markFailed(number); finish(true); return
        }
        handler.postDelayed({ clickDelete(number, 0) }, 400)
    }

    // ---------- Bước 2: bấm thùng rác trên thanh công cụ ----------
    private fun clickDelete(number: String, attempt: Int) {
        val btn = gmRoots().asSequence().flatMap { root ->
            findAll(root) { n ->
                isDeleteLabel(n.contentDescription) || isDeleteLabel(n.tooltipText) ||
                    (n.viewIdResourceName?.lowercase()?.let { it.contains("delete") || it.contains("trash") } == true)
            }.asSequence()
        }.firstOrNull()
        if (btn == null) {
            if (attempt < 3) { handler.postDelayed({ clickDelete(number, attempt + 1) }, 300); return }
            markFailed(number)
            performGlobalAction(GLOBAL_ACTION_BACK)   // thoát chế độ chọn
            return finish(true)
        }
        click(btn)
        handler.postDelayed({ confirm(number, 0) }, 400)
    }

    // ---------- Bước 3: bấm "Chuyển vào thùng rác" trong hộp thoại ----------
    private fun confirm(number: String, attempt: Int) {
        val btn = gmRoots().asSequence().flatMap { root ->
            findAll(root) { n -> plain(n.text) in CONFIRM_LABELS || plain(n.contentDescription) in CONFIRM_LABELS }.asSequence()
        }.lastOrNull()
        if (btn == null) {
            if (attempt < 5) { handler.postDelayed({ confirm(number, attempt + 1) }, 300); return }
            // Không thấy hộp thoại: có thể đã xoá luôn; nếu chưa thì sau 2 lần sẽ bỏ qua số này
            markFailed(number)
            deletedThisSession++
            return finish(true)
        }
        click(btn)
        deletedThisSession++
        failed.remove(number)
        finish(true)
    }

    private fun click(n: AccessibilityNodeInfo) {
        val target = clickableOf(n)
        val ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (!ok) n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun finish(rescan: Boolean) {
        busy = false
        if (rescan) handler.postDelayed({ scan() }, 500)
    }

    private fun markFailed(number: String) { failed[number] = (failed[number] ?: 0) + 1 }

    // ---------- Tìm dòng số lạ ----------
    private fun findSpamRow(r: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, String>? {
        val prefs = Prefs(this)
        val cache = HashMap<String, Boolean>()
        for (n in findAll(r) { it.text != null }) {
            val t = n.text.toString().trim()
            if (!SenderRule.looksLikeNumber(t) || !SenderRule.isPersonalNumber(t)) continue
            if ((failed[t] ?: 0) >= 2) continue
            val stranger = cache.getOrPut(t) { !prefs.isWhitelisted(t) && !ContactChecker.isKnownNumber(this, t) }
            if (!stranger) continue
            // Dòng trong danh sách có thể "giữ lâu"; tiêu đề trong màn hình chat thì không
            val row = longClickableAncestor(n) ?: continue
            return Pair(row, t)
        }
        return null
    }

    private fun longClickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isLongClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun findAll(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 3000) {
            val n = queue.removeFirst()
            visited++
            if (pred(n)) out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
        }
        return out
    }
}
