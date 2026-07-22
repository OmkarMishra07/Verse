package com.example.data.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Singleton OkHttpClient shared across all network helpers.
 *
 * Features:
 * - Shared connection pool (reduces DNS lookups, TCP handshakes)
 * - Automatic retry on 429 (rate limiting) with exponential backoff
 * - Consistent timeouts across all network calls
 * - Cookie jar for YouTube consent pages
 */
object NetworkClient {
    private const val TAG = "NetworkClient"

    /** Per-host rate limit tracking. Stores timestamp of last request per host. */
    private val lastRequestTimes = ConcurrentHashMap<String, Long>()
    private const val MIN_REQUEST_INTERVAL_MS = 1000L  // 1 second between requests to same host

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .cookieJar(object : okhttp3.CookieJar {
            private val cookieStore = ConcurrentHashMap<String, List<okhttp3.Cookie>>()
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        })
        .addInterceptor(RetryInterceptor())
        .build()

    /** Interceptor that retries on 429 rate limiting with exponential backoff. */
    private class RetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val host = request.url.host

            // Rate limit: ensure minimum interval between requests to same host
            val lastTime = lastRequestTimes[host] ?: 0L
            val elapsed = System.currentTimeMillis() - lastTime
            if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                Thread.sleep(MIN_REQUEST_INTERVAL_MS - elapsed)
            }
            lastRequestTimes[host] = System.currentTimeMillis()

            var response = chain.proceed(request)
            var retries = 0
            val maxRetries = 3

            while (response.code == 429 && retries < maxRetries) {
                retries++
                val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: (2L * retries)
                Log.w(TAG, "Rate limited (429) from $host, retrying in ${retryAfter}s (attempt $retries/$maxRetries)")
                response.close()
                Thread.sleep(retryAfter * 1000)
                lastRequestTimes[host] = System.currentTimeMillis()
                response = chain.proceed(request)
            }

            return response
        }
    }
}
