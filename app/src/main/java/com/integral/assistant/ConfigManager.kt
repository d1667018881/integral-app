package com.integral.assistant

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets
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
        const val DEFAULT_INTEGRAL_TYPE = ""
        const val DEFAULT_SUBMIT_URL = ""
        const val DEFAULT_QUERY_URL = ""
        const val DEFAULT_MAX_ATTEMPTS = 50
        const val DEFAULT_DELAY_MIN = 39
        const val DEFAULT_DELAY_MAX = 180
        const val DEFAULT_MODE = "reach" // reach=达标模式, increment=增加积分模式
    }

    fun getLoginId(): String = prefs.getString("login_id", DEFAULT_LOGIN_ID) ?: ""
    fun getIntegralType(): String = prefs.getString("integral_type", DEFAULT_INTEGRAL_TYPE) ?: ""
    fun getSubmitUrl(): String = prefs.getString("submit_url", DEFAULT_SUBMIT_URL) ?: ""
    fun getQueryUrl(): String = prefs.getString("query_url", DEFAULT_QUERY_URL) ?: ""
    fun getMaxAttempts(): Int = prefs.getInt("max_attempts", DEFAULT_MAX_ATTEMPTS)
    fun getDelayMin(): Int = prefs.getInt("delay_min", DEFAULT_DELAY_MIN)
    fun getDelayMax(): Int = prefs.getInt("delay_max", DEFAULT_DELAY_MAX)
    fun getTargetScore(): Int = prefs.getInt("target_score", 100)
    fun getMode(): String = prefs.getString("mode", DEFAULT_MODE) ?: DEFAULT_MODE

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
