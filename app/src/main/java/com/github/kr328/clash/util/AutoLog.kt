package com.github.kr328.clash.util

import com.github.kr328.clash.common.log.Log

/**
 * Structured logging helper for automation/external-control flows.
 * All entries are prefixed with [CMC] so you can filter logcat easily:
 *   adb logcat -s CMC
 */
object AutoLog {
    private const val TAG = "CMC"

    fun d(step: String, msg: String) =
        Log.d("[$step] $msg")

    fun i(step: String, msg: String) =
        Log.i("[$step] $msg")

    fun w(step: String, msg: String, t: Throwable? = null) =
        if (t != null) Log.w("[$step] $msg", t) else Log.w("[$step] $msg")

    fun e(step: String, msg: String, t: Throwable? = null) =
        if (t != null) Log.w("[$step] ERROR $msg", t) else Log.w("[$step] ERROR $msg")
}
