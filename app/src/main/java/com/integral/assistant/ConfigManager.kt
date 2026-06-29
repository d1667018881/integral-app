package com.integral.assistant

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.*

/**
 * 配置管理器 - 保存用户设置
 */
class ConfigManager(context: Context) {

    private val prefs: SharedPreferences = 
        context.getSharedPreferences("integral_config", Context.MODE_PRIVATE)

    companion object {
        // 默认配置
        const val DEFAULT_LOGIN_ID = ""
        const val DEFAULT_SITE_CODE = "zzrailway"
        const val DEFAULT_INTEGRAL_TYPE = ""
        const val DEFAULT_SUBMIT_URL = ""
        const val DEFAULT_QUERY_URL = ""
        const val DEFAULT_MAX_ATTEMPTS = 50
        const val DEFAULT_DELAY_MIN = 39
        const val DEFAULT_DELAY_MAX = 180
        const val RESOURCE_ID_RANGE = 100

        // 日期格式（兼容 API 24+）
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }

    fun getLoginId(): String = prefs.getString("login_id", DEFAULT_LOGIN_ID) ?: ""
    fun getSiteCode(): String = prefs.getString("site_code", DEFAULT_SITE_CODE) ?: ""
    fun getIntegralType(): String = prefs.getString("integral_type", DEFAULT_INTEGRAL_TYPE) ?: ""
    fun getSubmitUrl(): String = prefs.getString("submit_url", DEFAULT_SUBMIT_URL) ?: ""
    fun getQueryUrl(): String = prefs.getString("query_url", DEFAULT_QUERY_URL) ?: ""
    fun getMaxAttempts(): Int = prefs.getInt("max_attempts", DEFAULT_MAX_ATTEMPTS)
    fun getDelayMin(): Int = prefs.getInt("delay_min", DEFAULT_DELAY_MIN)
    fun getDelayMax(): Int = prefs.getInt("delay_max", DEFAULT_DELAY_MAX)

    fun saveLoginId(loginId: String) {
        prefs.edit().putString("login_id", loginId).apply()
    }
    fun saveSiteCode(siteCode: String) {
        prefs.edit().putString("site_code", siteCode).apply()
    }
    fun saveIntegralType(integralType: String) {
        prefs.edit().putString("integral_type", integralType).apply()
    }
    fun saveSubmitUrl(url: String) {
        prefs.edit().putString("submit_url", url).apply()
    }
    fun saveQueryUrl(url: String) {
        prefs.edit().putString("query_url", url).apply()
    }
    fun saveMaxAttempts(attempts: Int) {
        prefs.edit().putInt("max_attempts", attempts).apply()
    }
    fun saveDelayMin(delay: Int) {
        prefs.edit().putInt("delay_min", delay).apply()
    }
    fun saveDelayMax(delay: Int) {
        prefs.edit().putInt("delay_max", delay).apply()
    }

    // 重置所有配置为默认值（保留工号）
    fun resetToDefault() {
        val currentLoginId = getLoginId()
        prefs.edit()
            .clear()
            .putString("login_id", currentLoginId)
            .apply()
    }

    /**
     * 资源ID管理
     */
    private val resourcePrefs: SharedPreferences =
        context.getSharedPreferences("resource_data", Context.MODE_PRIVATE)

    fun getResourceId(): Int = resourcePrefs.getInt("resource_id", -1)
    fun getLastDate(): String = resourcePrefs.getString("last_date", "") ?: ""

    fun saveResourceId(id: Int) {
        resourcePrefs.edit().putInt("resource_id", id).apply()
    }
    fun saveLastDate(date: String) {
        resourcePrefs.edit().putString("last_date", date).apply()
    }

    // 获取当前日期字符串（兼容 API 24+）
    private fun getCurrentDateStr(): String {
        return DATE_FORMAT.format(Date())
    }

    // 初始化随机资源ID
    fun initResourceId() {
        val randomId = (1..RESOURCE_ID_RANGE).random()
        val today = getCurrentDateStr()
        resourcePrefs.edit()
            .putInt("resource_id", randomId)
            .putString("last_date", today)
            .apply()
    }

    // 检查是否需要跨日重置
    fun needDayReset(): Boolean {
        val lastDate = getLastDate()
        val today = getCurrentDateStr()
        return lastDate != today
    }
}
