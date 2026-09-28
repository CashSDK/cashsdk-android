package com.cashsdk.paywall

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cashsdk.CashSDK
import com.cashsdk.model.PaywallConfig

/**
 * Everything the renderer needs for one presentation. Not Parcelable (a [PaywallConfig] is an
 * arbitrarily large JSON tree), so it is handed off via the [PaywallActivity.pending] slot
 * rather than Intent extras — see [PaywallActivity.present].
 */
data class PaywallPresentation(
    val config: PaywallConfig,
    val placement: String,
    val variantId: String?,
    val experimentId: String?,
) {
    /**
     * Experiment attribution attached to paywall lifecycle events.
     *
     * `variantId` ALSO travels as the first-class `variant` field on the event (see
     * [com.cashsdk.CashSDKClient.track]) — `Event.variant` is a real column server-side and the
     * only one experiment reporting reads. Keeping it in `props` as well costs nothing and keeps
     * `experimentId`, which has no column, alongside it.
     */
    internal fun telemetryProps(): Map<String, Any> = buildMap {
        variantId?.let { put("variantId", it) }
        experimentId?.let { put("experimentId", it) }
    }
}

/**
 * Transparent host Activity for the Compose paywall. Started by the SDK (never the host app,
 * hence `exported=false`). On process death it is recreated with no [pending] payload and
 * simply finishes — the host advances, matching the graceful-degrade contract (FR-6.7).
 */
class PaywallActivity : ComponentActivity() {

    private var presentation: PaywallPresentation? = null
    private var closeLogged = false
    private var onFinish: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payload = pending
        pending = null
        onFinish = pendingOnFinish
        pendingOnFinish = null
        if (payload == null || !CashSDK.isConfigured) {
            // Recreated after process death (or not configured): still settle the caller so a
            // gated feature isn't stranded.
            finish()
            return
        }
        presentation = payload
        val client = CashSDK.shared

        enableEdgeToEdge()
        client.track(
            "paywall_open",
            placement = payload.placement,
            properties = payload.telemetryProps(),
            variant = payload.variantId,
        )

        setContent {
            PaywallScreen(
                presentation = payload,
                client = client,
                onDismiss = { finish() },
            )
        }
    }

    override fun finish() {
        // Emit paywall_close exactly once (dismiss, purchase-complete, or system close).
        presentation?.takeIf { !closeLogged }?.let {
            closeLogged = true
            if (CashSDK.isConfigured) {
                CashSDK.shared.track(
                    "paywall_close",
                    placement = it.placement,
                    properties = it.telemetryProps(),
                    variant = it.variantId,
                )
            }
        }
        val finishCallback = onFinish
        onFinish = null
        super.finish()
        finishCallback?.invoke()
    }

    companion object {
        // Single-slot handoff for the config (see PaywallPresentation kdoc).
        @Volatile
        internal var pending: PaywallPresentation? = null

        /**
         * Fires exactly once when the paywall closes. Drives feature gating, so it must run
         * even on the process-death path where [pending] is gone — otherwise a gated feature
         * would hang forever waiting for a callback that never comes.
         */
        @Volatile
        internal var pendingOnFinish: (() -> Unit)? = null

        /** Launch the paywall over the host UI. Uses NEW_TASK since [context] may be app context. */
        fun present(
            context: Context,
            presentation: PaywallPresentation,
            onFinish: (() -> Unit)? = null,
        ) {
            pending = presentation
            pendingOnFinish = onFinish
            val intent = Intent(context, PaywallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
