package com.integral.assistant

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

/**
 * 多账号管理：每个账号独立保存工号、模式、目标值，
 * 以及各自的资源ID/日期（用于跨次运行连续提交）。
 * 网络类设置（提交/查询URL、积分类型、次数、延迟）为全局共享，不在此处。
 */
data class Account(
    val id: String,
    var loginId: String,
    var mode: String,        // "reach"=达标模式, "increment"=增加积分模式
    var target: Int,         // 达标模式=目标积分; 增加积分模式=要增加的分数
    var note: String? = null // 备注：便于多账号区分（旧数据无此字段时为 null，展示处用 orEmpty() 兜底）
)

class AccountManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("accounts", Context.MODE_PRIVATE)
    private val resPrefs: SharedPreferences =
        context.getSharedPreferences("account_resource", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        const val RESOURCE_ID_RANGE = 100
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }

    fun getAccounts(): MutableList<Account> {
        val json = prefs.getString("account_list", null)
        if (json.isNullOrEmpty()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<Account>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveAccounts(list: List<Account>) {
        prefs.edit().putString("account_list", gson.toJson(list)).apply()
    }

    fun getCurrentAccountId(): String = prefs.getString("current_account", "") ?: ""

    fun setCurrentAccountId(id: String) {
        prefs.edit().putString("current_account", id).apply()
    }

    fun addAccount(account: Account) {
        val list = getAccounts()
        list.add(account)
        saveAccounts(list)
    }

    fun removeAccount(id: String) {
        val list = getAccounts().filter { it.id != id }.toMutableList()
        saveAccounts(list)
        // 清理该账号的资源数据（含旧格式 res_ 残留）
        resPrefs.edit()
            .remove(resListKey(id))
            .remove(resPtrKey(id))
            .remove(resUsedKey(id))
            .remove("date_$id")
            .remove("res_$id")
            .apply()
    }

    // ---- 每个账号独立的资源ID / 日期 ----
    // 资源ID 采用「随机排列（Fisher-Yates 洗牌）」：把 1..RESOURCE_ID_RANGE 打乱后
    // 依次取用。相比原来的「随机起点 + 每次 +1」，既保证单个排列周期内绝对不重复，
    // 又消除了 +1 的单调性（顺序随机、无规律）。跨天或取完则重新洗牌。

    private fun resListKey(id: String) = "reslist_$id"
    private fun resPtrKey(id: String) = "resptr_$id"
    private fun resUsedKey(id: String) = "resused_$id"

    /** 返回当前指针处的资源ID；未初始化或越界返回 -1（调用方据此重新初始化） */
    fun getResourceId(accountId: String): Int {
        val json = resPrefs.getString(resListKey(accountId), null) ?: return -1
        val ptr = resPrefs.getInt(resPtrKey(accountId), 0)
        return try {
            val list = gson.fromJson<List<Int>>(json, object : TypeToken<List<Int>>() {}.type)
            if (ptr < 0 || ptr >= list.size) -1 else list[ptr]
        } catch (e: Exception) {
            -1
        }
    }

    private fun getUsedSet(accountId: String): Set<Int> {
        val json = resPrefs.getString(resUsedKey(accountId), null) ?: return emptySet()
        return try {
            gson.fromJson(json, object : TypeToken<Set<Int>>() {}.type)
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * 用掉当前资源ID后推进：把当前值记入「当日已用集合」，指针跳过已用值。
     * 即使某天提交次数超过范围、触发重新洗牌，也不会与当天已用过的值重复；
     * 仅当 1..RANGE 全部用尽（单日物理极限）才清空重洗。
     */
    fun advanceResourceId(accountId: String) {
        val json = resPrefs.getString(resListKey(accountId), null) ?: run { initResourceId(accountId); return }
        val list = try {
            gson.fromJson<List<Int>>(json, object : TypeToken<List<Int>>() {}.type)
        } catch (e: Exception) { initResourceId(accountId); return }
        var ptr = resPrefs.getInt(resPtrKey(accountId), 0)
        if (ptr < 0 || ptr >= list.size) { initResourceId(accountId); return }

        val used = getUsedSet(accountId).toMutableSet().apply { add(list[ptr]) }
        var next = ptr + 1
        while (next < list.size && list[next] in used) next++

        val editor = resPrefs.edit().putString(resUsedKey(accountId), gson.toJson(used))
        if (next >= list.size) {
            // 1..RANGE 已全部用过（单日极罕见），重新洗牌并清空已用集合后继续
            val newList = (1..RESOURCE_ID_RANGE).toList().shuffled()
            editor.putString(resListKey(accountId), gson.toJson(newList))
                .putInt(resPtrKey(accountId), 0)
        } else {
            editor.putInt(resPtrKey(accountId), next)
        }
        editor.apply()
    }

    fun getLastDate(accountId: String): String = resPrefs.getString("date_$accountId", "") ?: ""

    fun saveLastDate(accountId: String, date: String) {
        resPrefs.edit().putString("date_$accountId", date).apply()
    }

    fun needDayReset(accountId: String): Boolean {
        val last = getLastDate(accountId)
        val today = DATE_FORMAT.format(Date())
        // 日期变更，或尚未初始化（无排列数据），都需要重置
        return last != today || !resPrefs.contains(resListKey(accountId))
    }

    fun initResourceId(accountId: String) {
        val list = (1..RESOURCE_ID_RANGE).toList().shuffled()
        val today = DATE_FORMAT.format(Date())
        resPrefs.edit()
            .putString(resListKey(accountId), gson.toJson(list))
            .putInt(resPtrKey(accountId), 0)
            .putString(resUsedKey(accountId), gson.toJson(emptySet<Int>()))
            .putString("date_$accountId", today)
            .apply()
    }
}
