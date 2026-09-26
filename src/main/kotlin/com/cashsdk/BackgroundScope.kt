package com.cashsdk

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * The scope for the SDK's own background work (entitlement reads, the deadline watch, sync,
 * telemetry). Nothing the host app calls runs here, so nothing here may crash the host app: an
 * uncaught failure is logged and that one job ends, while every other job carries on.
 */
internal fun sdkBackgroundScope(
    dispatcher: CoroutineDispatcher,
    log: (Throwable) -> Unit = ::logBackgroundFailure,
): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> log(error) })

internal fun logBackgroundFailure(error: Throwable) {
    runCatching { Log.w("CashSDK", "Background work failed and was stopped", error) }
}
