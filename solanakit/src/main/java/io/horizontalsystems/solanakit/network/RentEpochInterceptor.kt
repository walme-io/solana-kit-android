package io.horizontalsystems.solanakit.network

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * OkHttp interceptor that fixes rentEpoch overflow issue.
 * Solana's rentEpoch can be 18446744073709551615 (u64::MAX) which
 * exceeds Long.MAX_VALUE (9223372036854775807).
 * This interceptor replaces such values with 0 before JSON parsing.
 */
class RentEpochInterceptor : Interceptor {

    companion object {
        // u64::MAX as string
        private const val U64_MAX = "18446744073709551615"
        // Regex to match rentEpoch with u64::MAX value
        private val RENT_EPOCH_PATTERN = Regex("\"rentEpoch\"\\s*:\\s*$U64_MAX")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Only process JSON responses
        val contentType = response.body?.contentType()
        if (contentType?.subtype != "json") {
            return response
        }

        val originalBody = response.body?.string() ?: return response

        // Replace u64::MAX rentEpoch values with 0
        val fixedBody = if (originalBody.contains(U64_MAX)) {
            RENT_EPOCH_PATTERN.replace(originalBody, "\"rentEpoch\":0")
        } else {
            originalBody
        }

        // Create new response with fixed body
        val newBody = fixedBody.toResponseBody(contentType)
        return response.newBuilder()
            .body(newBody)
            .build()
    }
}
