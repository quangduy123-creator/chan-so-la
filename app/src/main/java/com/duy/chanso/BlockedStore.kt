package com.duy.chanso

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Tin bị chặn (chỉ lưu khi bật "Giữ tin bị chặn 7 ngày"), nằm trong bộ nhớ riêng của app, không vào hộp thư */
object BlockedStore {
    data class Item(val address: String, val body: String, val time: Long, val subId: Int)

    private const val MAX = 500
    private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
    private fun sp(ctx: Context) = ctx.getSharedPreferences("blocked", Context.MODE_PRIVATE)

    @Synchronized
    fun add(ctx: Context, address: String, body: String, subId: Int) {
        val list = all(ctx).toMutableList()
        list.add(0, Item(address, body, System.currentTimeMillis(), subId))
        save(ctx, list.take(MAX))
    }

    @Synchronized
    fun all(ctx: Context): List<Item> {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        return try {
            val arr = JSONArray(sp(ctx).getString("items", "[]"))
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .map { Item(it.optString("a"), it.optString("b"), it.optLong("t"), it.optInt("s", -1)) }
                .filter { it.time >= cutoff }
        } catch (e: Exception) { emptyList() }
    }

    @Synchronized
    fun remove(ctx: Context, item: Item) {
        save(ctx, all(ctx).filterNot { it.time == item.time && it.address == item.address })
    }

    @Synchronized
    fun clear(ctx: Context) { sp(ctx).edit().remove("items").apply() }

    private fun save(ctx: Context, list: List<Item>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("a", it.address).put("b", it.body).put("t", it.time).put("s", it.subId)) }
        sp(ctx).edit().putString("items", arr.toString()).apply()
    }
}
