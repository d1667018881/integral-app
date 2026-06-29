package com.integral.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import com.google.gson.Gson
import com.google.gson.JsonObject
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

                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .post(body)
                        .header("Connection", "keep-alive")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; Pixel 9 Pro Build/BP2A.250305.019; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36 whatyApp whatyApiApp")
                        .build()

                    val response = client.newCall(request).execute()
                    response.body?.string() ?: throw IOException("Empty response")
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
     * 提交积分
     * 对应另一个 AI 的 do_submit 函数
     */
    suspend fun submitIntegral(
        loginId: String,
        siteCode: String,
        integralType: String,
        submitUrl: String,
        resourceId: Int
    ): Int {
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
