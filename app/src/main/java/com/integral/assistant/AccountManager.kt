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
        // 资源ID 模型：随机起点 + 每次随机 +n
        const val RESOURCE_ID_START_MIN = 0      // 起点随机下限（含）
        const val RESOURCE_ID_START_MAX = 99999  // 起点随机上限（含）
        const val RESOURCE_ID_STEP_MIN = 1       // 每次步进下限（含），>=1 保证严格递增
        const val RESOURCE_ID_STEP_MAX = 1000    // 每次步进上限（含）
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
            .remove(resBaseKey(id))
            .remove("date_$id")
            .remove("res_$id")
            .apply()
    }

    // ---- 每个账号独立的资源ID / 日期 ----
    // 资源ID 模型：「随机起点 + 每次随机 +n」
    //   - 首次初始化：起点在 [RESOURCE_ID_START_MIN, RESOURCE_ID_START_MAX] 间随机取一个
    //   - 每次成功提交后：resourceId = 当前值 + 随机步长[STEP_MIN, STEP_MAX]
    // 因为步长恒为正（>=STEP_MIN=1），序列严格单调递增，天然保证「绝对不重复」；
    // 步长随机又消除了 +1 的单调性，无规律可循。后端不限制范围，可一路增长。
    // 初始化后不再因跨天重置（避免打断单调递增序列），仅在从未初始化（无 base）时才初始化。

    private fun resBaseKey(id: String) = "resbase_$id"

    /** 返回当前资源ID（base）；未初始化返回 -1（调用方据此初始化） */
    fun getResourceId(accountId: String): Int {
        return resPrefs.getInt(resBaseKey(accountId), -1)
    }

    /**
     * 提交成功后推进：当前值 + 随机步长（步长>=1 保证严格递增、永不重复）。
     * 未初始化时先做一次初始化。
     */
    fun advanceResourceId(accountId: String) {
        val cur = resPrefs.getInt(resBaseKey(accountId), -1)
        if (cur < 0) { initResourceId(accountId); return }
        val step = (RESOURCE_ID_STEP_MIN..RESOURCE_ID_STEP_MAX).random()
        resPrefs.edit().putInt(resBaseKey(accountId), cur + step).apply()
    }

    fun getLastDate(accountId: String): String = resPrefs.getString("date_$accountId", "") ?: ""

    fun saveLastDate(accountId: String, date: String) {
        resPrefs.edit().putString("date_$accountId", date).apply()
    }

    /** 仅在从未初始化（无 base）时需要重置，避免打断单调递增序列 */
    fun needDayReset(accountId: String): Boolean {
        return !resPrefs.contains(resBaseKey(accountId))
    }

    fun initResourceId(accountId: String) {
        val start = (RESOURCE_ID_START_MIN..RESOURCE_ID_START_MAX).random()
        val today = DATE_FORMAT.format(Date())
        resPrefs.edit()
            .putInt(resBaseKey(accountId), start)
            .putString("date_$accountId", today)
            .apply()
    }
}
