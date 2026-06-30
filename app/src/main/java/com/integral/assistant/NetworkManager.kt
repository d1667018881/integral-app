package com.integral.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.io.IOException

/**
 * 网络请求管理器 - 对应另一个 AI 的 safe_post 函数
 */
class NetworkManager {

    private val client: OkHttpClient by lazy {
        // 创建支持自签名的 OkHttpClient
        val trustAllCerts = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAllCerts), SecureRandom())

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()

    /**
     * 发送 POST 请求（带重试机制）
     * 注意：此方法已在 IO 线程执行，调用方不需要再切换线程
     */
    suspend fun safePost(url: String, data: Map<String, String>): String {
        val maxRetries = 3
        val backoffFactor = 2L
        val initialDelay = 2000L

        repeat(maxRetries) { attempt ->
            try {
                return withContext(Dispatchers.IO) {
                    val body = FormBody.Builder().apply {
                        data.forEach { (key, value) ->
                            add(key, value)
                        }
                    }.build()

                    val request = Request.Builder()
                        .url(url)
                        .post(body)
                        .header("Connection", "keep-alive")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; Pixel 9 Pro Build/BP2A.250305.019; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 whatyApp whatyApiApp")
                        .build()

                    val response = client.newCall(request).execute()
                    response.use {
                        it.body?.string() ?: throw IOException("Empty response")
                    }
                }
            } catch (e: IOException) {
                // 网络异常，重试
                if (attempt < maxRetries - 1) {
                    val delayTime = initialDelay * (backoffFactor shl attempt)
                    delay(delayTime)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                // 其他异常，不重试直接抛出
                throw e
            }
        }
        throw IOException("All retries failed")
    }

    /**
     * 从网页抓取配置 JSON
     * 支持多种网页格式，按优先级依次尝试
     */
    suspend fun fetchConfigFromUrl(url: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G960U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36")
                    .build()

                val response = client.newCall(request).execute()
                val html = response.use { it.body?.string() } ?: return@withContext null

                // 按优先级尝试多种提取策略
                extractJsonFromHtml(html)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 通用 JSON 提取器
     * 按优先级尝试多种策略，哪个成功用哪个
     */
    private fun extractJsonFromHtml(html: String): String? {
        // 策略 1：页面本身就是纯 JSON
        val trimmed = html.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            if (validateConfigJson(trimmed)) return trimmed
        }

        // 策略 2：微云分享页面的 html_content 字段
        extractFromWeiyunHtml(html)?.let { json ->
            if (validateConfigJson(json)) return json
        }

        // 策略 3：<pre> 或 <code> 标签中的 JSON
        extractFromPreCodeTags(html)?.let { json ->
            if (validateConfigJson(json)) return json
        }

        // 策略 4：<script> 标签中的 JSON 变量
        extractFromScriptTags(html)?.let { json ->
            if (validateConfigJson(json)) return json
        }

        // 策略 5：JSONP 格式 callback({...})
        extractFromJsonp(html)?.let { json ->
            if (validateConfigJson(json)) return json
        }

        // 策略 6：整个页面中搜索 JSON 对象模式
        extractFromRawText(html)?.let { json ->
            if (validateConfigJson(json)) return json
        }

        return null
    }

    /**
     * 策略 2：微云分享页面
     */
    private fun extractFromWeiyunHtml(html: String): String? {
        val htmlContentPattern = "\"html_content\":\"([^\"]+)\"".toRegex()
        htmlContentPattern.find(html)?.let { match ->
            val encodedJson = match.groupValues[1]
            val decodedJson = encodedJson
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\u0026", "&")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            
            return extractJsonFromDecodedHtml(decodedJson)
        }
        return null
    }

    /**
     * 策略 3：从 <pre> 或 <code> 标签中提取
     */
    private fun extractFromPreCodeTags(html: String): String? {
        val prePattern = "<(?:pre|code)[^>]*>([\\s\\S]*?)</(?:pre|code)>".toRegex(RegexOption.IGNORE_CASE)
        prePattern.findAll(html).forEach { match ->
            val content = match.groupValues[1]
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .trim()
            
            if (content.startsWith("{")) {
                return content
            }
            val jsonStart = content.indexOf("{")
            val jsonEnd = content.lastIndexOf("}")
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                return content.substring(jsonStart, jsonEnd + 1)
            }
        }
        return null
    }

    /**
     * 策略 4：从 <script> 标签中提取 JSON 变量
     */
    private fun extractFromScriptTags(html: String): String? {
        val scriptPattern = "<script[^>]*>([\\s\\S]*?)</script>".toRegex(RegexOption.IGNORE_CASE)
        scriptPattern.findAll(html).forEach { match ->
            val scriptContent = match.groupValues[1]
            
            // 查找 var/const/let xxx = {...}; 格式
            val varPattern = "(?:var|const|let)\\s+\\w+\\s*=\\s*(\\{[\\s\\S]*?\\});".toRegex()
            varPattern.find(scriptContent)?.let { varMatch ->
                return varMatch.groupValues[1]
            }
            
            // 查找 JSON.parse('...') 或 JSON.parse("...")
            val parsePattern = "JSON\\.parse\\s*\\(\\s*['\"]([\\s\\S]*?)['\"]\\s*\\)".toRegex()
            parsePattern.find(scriptContent)?.let { parseMatch ->
                val jsonStr = parseMatch.groupValues[1]
                    .replace("\\'", "'")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                return jsonStr
            }
        }
        return null
    }

    /**
     * 策略 5：JSONP 格式 callback({...})
     */
    private fun extractFromJsonp(html: String): String? {
        val jsonpPattern = "\\w+\\s*\\(\\s*(\\{[\\s\\S]*?\\})\\s*\\)".toRegex()
        jsonpPattern.find(html)?.let { match ->
            return match.groupValues[1]
        }
        return null
    }

    /**
     * 策略 6：从纯文本中搜索 JSON 对象
     */
    private fun extractFromRawText(html: String): String? {
        // 去掉 HTML 标签
        val textOnly = html.replace("<[^>]+>".toRegex(), " ")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim()
        
        // 查找 { "submit_url": ... } 格式的 JSON
        val jsonPattern = "\\{[\\s\\S]*?\"submit_url\"[\\s\\S]*?\\}".toRegex()
        jsonPattern.find(textOnly)?.let { match ->
            return match.value
        }
        
        // 查找任何看起来像 JSON 的对象
        val genericJsonPattern = "\\{[\\s\\S]*?\"[\\w_]+\"\\s*:[\\s\\S]*?\\}".toRegex()
        genericJsonPattern.find(textOnly)?.let { match ->
            return match.value
        }
        
        return null
    }

    /**
     * 从解码后的微云 HTML 内容中提取纯 JSON
     */
    private fun extractJsonFromDecodedHtml(decodedHtml: String): String? {
        var textContent = decodedHtml
            .replace("<div>", "\n")
            .replace("</div>", "")
            .replace("&nbsp;", " ")
            .replace("<br>", "\n")
            .replace("\\n", "\n")
            .trim()

        // 去掉 URL 中的超链接标签 <a href="...">...</a>
        val anchorPattern = "<a[^>]*href=\"([^\"]*)\"[^>]*>([^<]*)</a>".toRegex()
        textContent = anchorPattern.replace(textContent) { matchResult ->
            matchResult.groupValues[2]
        }

        // 查找 JSON 对象
        val jsonStart = textContent.indexOf("{")
        val jsonEnd = textContent.lastIndexOf("}")
        
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return textContent.substring(jsonStart, jsonEnd + 1)
        }

        return null
    }

    /**
     * 验证从网页获取的 JSON 是否包含有效的配置字段
     */
    fun validateConfigJson(jsonString: String): Boolean {
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val configMap: Map<String, String> = gson.fromJson(jsonString, type)
            
            // 至少包含一个有效字段
            configMap.containsKey("submit_url") ||
            configMap.containsKey("query_url") ||
            configMap.containsKey("integral_type") ||
            configMap.containsKey("max_attempts") ||
            configMap.containsKey("delay_min") ||
            configMap.containsKey("delay_max")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 提交积分
     * 对应另一个 AI 的 do_submit 函数
     */
    suspend fun submitIntegral(
        loginId: String,
        integralType: String,
        submitUrl: String,
        resourceId: Int
    ): Int {
        // 站点代码写死在代码中，不从外部传入
        val siteCode = "zzrailway"
        
        val data = mapOf(
            "loginId" to loginId,
            "siteCode" to siteCode,
            "integralType" to integralType,
            "resourceId" to (resourceId + 1).toString()
        )

        safePost(submitUrl, data)
        return resourceId + 1
    }

    /**
     * 查询积分
     * 对应另一个 AI 的 do_query 函数
     */
    suspend fun queryIntegral(
        loginId: String,
        queryUrl: String
    ): Int {
        val data = mapOf(
            "page.curPage" to "1",
            "page.pageSize" to "20",
            "page.searchItem.loginId" to loginId,
            "page.searchItem.queryId" to "getStudentIntegral"
        )

        val response = safePost(queryUrl, data)
        val json = gson.fromJson(response, JsonObject::class.java)

        val items = json.getAsJsonObject("page")
            ?.getAsJsonArray("items")
            ?: throw IllegalStateException("API returned empty data")

        if (items.size() == 0) {
            throw IllegalStateException("items array is empty")
        }

        val infoArray = items[0].asJsonObject
            .getAsJsonArray("info")
            ?: throw IllegalStateException("Score info is empty")

        if (infoArray.size() == 0) {
            throw IllegalStateException("info array is empty")
        }

        val score = infoArray[0].asJsonObject
            .get("score")
            ?.asInt
            ?: throw IllegalStateException("score not found")

        return score
    }
}