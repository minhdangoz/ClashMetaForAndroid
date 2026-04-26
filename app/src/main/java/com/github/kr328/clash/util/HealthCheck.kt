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

    suspend fun run(): Result = withContext(Dispatchers.IO) {
        AutoLog.d("HealthCheck", "Starting check → $CHECK_URL")

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
                    "HTTP $code in ${latency}ms → ${if (ok) "PASS" else "FAIL (expected $EXPECTED)"}"
                )

                Result(
                    ok,
                    code,
                    latency,
                    if (ok) "Proxy OK (${latency}ms)" else "Unexpected HTTP $code"
                )
            }.getOrElse { t ->
                AutoLog.e("HealthCheck", "Exception: ${t.message}", t)
                Result(false, -1, -1, "Error: ${t.message}")
            }
        }

        result ?: run {
            AutoLog.w("HealthCheck", "Timed out after ${TIMEOUT_MS}ms")
            Result(false, -1, -1, "Timeout after ${TIMEOUT_MS}ms")
        }
    }
}