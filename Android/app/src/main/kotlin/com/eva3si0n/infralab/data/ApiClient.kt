package com.eva3si0n.infralab.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val json = Json { ignoreUnknownKeys = true }

    suspend fun get(url: String, token: String = ""): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .apply { if (token.isNotEmpty()) addHeader("Authorization", "Bearer $token") }
            .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            response.body?.string() ?: throw Exception("Empty response body")
        }
    }

    inline fun <reified T> decode(body: String): T = json.decodeFromString(body)
}
