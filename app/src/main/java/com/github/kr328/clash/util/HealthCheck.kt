package com.github.kr328.clash.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Performs a connectivity health check via Google's generate_204 endpoint.
 *
 * When the VPN is active this request goes through the tunnel, so a 204
 * response proves the proxy has working internet.
 *
 * Usage:
 *   val result = HealthCheck.run()
 *   result.ok        // true = proxy has internet
 *   result.httpCode  // 204 on success, -1 on timeout/error
 *   result.message   // human-readable summary
 */
object HealthCheck {

    private const val CHECK_URL   = "http://connectivitycheck.gstatic.com/generate_204"
    private const val TIMEOUT_MS  = 5_000           // 5 s total budget
    private const val CONNECT_MS  = 3_000
    private const val READ_MS     = 3_000
    private const val EXPECTED    = 204

    data class Result(
        val ok: Boolean,
        val httpCode: Int,
        val latencyMs: Long,
        val message: String,
    )

    suspend fun run(retries: Int = 3): Result = withContext(Dispatchers.IO) {
        AutoLog.d("HealthCheck", "Starting check → $CHECK_URL (retries=$retries)")

        repeat(retries) { attempt ->
            val attemptIndex = attempt + 1
            AutoLog.d("HealthCheck", "Attempt $attemptIndex/$retries")

            val result = withTimeoutOrNull(TIMEOUT_MS.toLong()) {
                runCatching {
                    val t0 = System.currentTimeMillis()

                    val conn = (URL(CHECK_URL).openConnection() as HttpURLConnection).apply {
                        connectTimeout = CONNECT_MS
                        readTimeout = READ_MS
                        instanceFollowRedirects = false
                        requestMethod = "GET"
                        setRequestProperty("Connection", "close")
                    }

                    val code = conn.responseCode
                    val latency = System.currentTimeMillis() - t0
                    conn.disconnect()

                    val ok = (code == EXPECTED)

                    AutoLog.i(
                        "HealthCheck",
                        "Attempt $attemptIndex → HTTP $code in ${latency}ms → ${if (ok) "PASS" else "FAIL"}"
                    )

                    Result(
                        ok,
                        code,
                        latency,
                        if (ok) "Proxy OK (${latency}ms)" else "Unexpected HTTP $code"
                    )
                }.getOrElse { t ->
                    AutoLog.e("HealthCheck", "Attempt $attemptIndex exception: ${t.message}", t)
                    Result(false, -1, -1, "Error: ${t.message}")
                }
            } ?: Result(false, -1, -1, "Timeout after ${TIMEOUT_MS}ms")

            // ✅ success → return immediately
            if (result.ok) return@withContext result

            // ⏳ optional small delay before retry (except last attempt)
            if (attemptIndex < retries) {
                kotlinx.coroutines.delay(300)
            }
        }

        // ❌ all attempts failed
        AutoLog.w("HealthCheck", "All $retries attempts failed")
        Result(false, -1, -1, "Failed after $retries attempts")
    }
}