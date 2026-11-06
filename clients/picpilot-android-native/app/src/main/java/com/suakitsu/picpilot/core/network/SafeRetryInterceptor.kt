package com.suakitsu.picpilot.core.network

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Retries only read-only requests and quote lookups. Generation and payment calls are never replayed. */
class SafeRetryInterceptor(
    private val retryDelayMillis: Long = 250,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.isSafeToRetry()) return chain.proceed(request)

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val response = chain.proceed(request)
                if (attempt == 0 && response.code in RETRYABLE_STATUS_CODES) {
                    response.close()
                    pauseBeforeRetry()
                } else {
                    return response
                }
            } catch (error: IOException) {
                if (attempt == MAX_ATTEMPTS - 1) throw error
                pauseBeforeRetry()
            }
        }
        error("unreachable")
    }

    private fun pauseBeforeRetry() {
        try {
            Thread.sleep(retryDelayMillis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("retry interrupted", interrupted)
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 2
        private val RETRYABLE_STATUS_CODES = setOf(502, 503, 504)
    }
}

internal fun Request.isSafeToRetry(): Boolean =
    method.equals("GET", ignoreCase = true) ||
        method.equals("HEAD", ignoreCase = true) ||
        (method.equals("POST", ignoreCase = true) && url.encodedPath.endsWith("/estimate"))
