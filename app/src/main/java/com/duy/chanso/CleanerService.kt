package com.duy.chanso

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Khi bạn mở Google Tin nhắn: tìm các cuộc trò chuyện có tiêu đề là SỐ LẠ
 * (số điện thoại thường, không có trong danh bạ / danh sách cho phép) rồi tự xoá:
 *   giữ lâu dòng đó -> bấm nút Xoá trên thanh công cụ -> xác nhận Xoá.
 * Không phủ lớp che, không thông báo.
 */
class CleanerService : AccessibilityService() {

    companion object {
        const val GM = "com.google.android.apps.messaging"
        private val DELETE_WORDS = listOf("xóa", "xoá", "delete")
        private const val MAX_PER_SESSION = 40
    }

    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var scheduled = false
    private var deletedThisSession = 0
    private val failed = HashMap<String, Int>()
    private var lastPkgActive = false

    override fun onServiceConnected() {}
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (pkg != GM) {
            if (lastPkgActive && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // Rời Google Tin nhắn -> bắt đầu phiên mới cho lần mở sau
                lastPkgActive = false
                deletedThisSession = 0
                failed.clear()
            }
            return
        }
        lastPkgActive = true
        val p = Prefs(this)
        if (!p.autoClean || !p.blockSms) return
        if (busy || scheduled) return
        scheduled = true
        handler.postDelayed({ scheduled = false; scan() }, 600)
    }

    private fun root(): AccessibilityNodeInfo? =
        rootInActiveWindow?.takeIf { it.packageName?.toString() == GM }

    // ---------- Bước 1: tìm & giữ lâu một dòng số lạ ----------
    private fun scan() {
        if (busy || deletedThisSession >= MAX_PER_SESSION) return
        val r = root() ?: return
        val (row, number) = findSpamRow(r) ?: return
        busy = true
        if (!row.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            markFailed(number); finish(false); return
        }
        handler.postDelayed({ clickDelete(number) }, 500)
    }

    // ---------- Bước 2: bấm nút Xoá trên thanh công cụ ----------
    private fun clickDelete(number: String) {
        val r = root() ?: return finish(false)
        val btn = findAll(r) { n ->
            val d = n.contentDescription?.toString()?.lowercase()?.trim() ?: return@findAll false
            DELETE_WORDS.any { d == it || d.startsWith("$it ") }
        }.firstOrNull()
        if (btn == null) {
            markFailed(number)
            performGlobalAction(GLOBAL_ACTION_BACK)   // thoát chế độ chọn
            return finish(false)
        }
        clickableOf(btn)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({ confirm(number) }, 500)
    }

    // ---------- Bước 3: xác nhận trong hộp thoại ----------
    private fun confirm(number: String) {
        val r = rootInActiveWindow ?: return finish(false)
        val btn = findAll(r) { n ->
            val t = n.text?.toString()?.lowercase()?.trim() ?: return@findAll false
            DELETE_WORDS.contains(t)
        }.lastOrNull()
        if (btn == null) {
            // Có máy xoá luôn không hỏi lại; nếu chưa xoá được thì lần quét sau sẽ bỏ qua sau 2 lần thử
            markFailed(number)
            deletedThisSession++
            return finish(true)
        }
        clickableOf(btn)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        deletedThisSession++
        failed.remove(number)
        finish(true)
    }

    private fun finish(rescan: Boolean) {
        busy = false
        if (rescan) handler.postDelayed({ scan() }, 700)
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
        return n
    }

    private fun findAll(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 3000) {
            val n = stack.removeFirst()
            visited++
            if (pred(n)) out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        return out
    }
}
