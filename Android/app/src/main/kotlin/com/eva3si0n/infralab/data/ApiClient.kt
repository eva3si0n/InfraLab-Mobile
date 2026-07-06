package com.eva3si0n.infralab.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    // POST a JSON body; returns the response body even on 4xx/5xx (so the caller can read the
    // service's {"error":...}). Throws only on transport/empty-body failures.
    suspend fun post(url: String, jsonBody: String, token: String = ""): String = withContext(Dispatchers.IO) {
        val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .apply { if (token.isNotEmpty()) addHeader("Authorization", "Bearer $token") }
            .build()
        client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw Exception("Empty response body")
        }
    }

    inline fun <reified T> decode(body: String): T = json.decodeFromString(body)
}
