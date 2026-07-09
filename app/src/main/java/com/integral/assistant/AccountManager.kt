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
    var target: Int          // 达标模式=目标积分; 增加积分模式=要增加的分数
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
        // 清理该账号的资源数据
        resPrefs.edit()
            .remove("res_$id")
            .remove("date_$id")
            .apply()
    }

    // ---- 每个账号独立的资源ID / 日期 ----

    fun getResourceId(accountId: String): Int = resPrefs.getInt("res_$accountId", -1)

    fun saveResourceId(accountId: String, id: Int) {
        resPrefs.edit().putInt("res_$accountId", id).apply()
    }

    fun getLastDate(accountId: String): String = resPrefs.getString("date_$accountId", "") ?: ""

    fun saveLastDate(accountId: String, date: String) {
        resPrefs.edit().putString("date_$accountId", date).apply()
    }

    fun needDayReset(accountId: String): Boolean {
        val last = getLastDate(accountId)
        val today = DATE_FORMAT.format(Date())
        return last != today
    }

    fun initResourceId(accountId: String) {
        val randomId = (1..RESOURCE_ID_RANGE).random()
        val today = DATE_FORMAT.format(Date())
        saveResourceId(accountId, randomId)
        saveLastDate(accountId, today)
    }
}
