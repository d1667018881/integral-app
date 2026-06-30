package com.integral.assistant

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets
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

    /**
     * 导出用户配置为 Base64 编码字符串
     * 避免 JSON 中的特殊字符被网页转义或添加超链接
     */
    fun exportConfig(): String {
        val configMap = mapOf(
            "submit_url" to getSubmitUrl(),
            "query_url" to getQueryUrl(),
            "integral_type" to getIntegralType(),
            "max_attempts" to getMaxAttempts().toString(),
            "delay_min" to getDelayMin().toString(),
            "delay_max" to getDelayMax().toString()
        )
        val json = Gson().toJson(configMap)
        // Base64 编码，避免特殊字符问题
        return Base64.encodeToString(json.toByteArray(StandardCharsets.UTF_8), Base64.DEFAULT)
    }

    /**
     * 从字符串导入用户配置
     * 先尝试 Base64 解码，失败则尝试直接解析 JSON
     */
    fun importConfig(configString: String): Boolean {
        return try {
            val jsonString = if (isBase64(configString)) {
                // Base64 解码
                String(Base64.decode(configString, Base64.DEFAULT), StandardCharsets.UTF_8)
            } else {
                // 直接当作 JSON
                configString
            }

            val type = object : TypeToken<Map<String, String>>() {}.type
            val configMap: Map<String, String> = Gson().fromJson(jsonString, type)

            configMap["submit_url"]?.let { saveSubmitUrl(it) }
            configMap["query_url"]?.let { saveQueryUrl(it) }
            configMap["integral_type"]?.let { saveIntegralType(it) }
            configMap["max_attempts"]?.toIntOrNull()?.let { saveMaxAttempts(it) }
            configMap["delay_min"]?.toIntOrNull()?.let { saveDelayMin(it) }
            configMap["delay_max"]?.toIntOrNull()?.let { saveDelayMax(it) }

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 判断字符串是否为 Base64 编码
     */
    private fun isBase64(str: String): Boolean {
        return try {
            Base64.decode(str, Base64.DEFAULT)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
