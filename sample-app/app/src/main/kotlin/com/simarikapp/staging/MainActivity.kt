package com.simarikapp.staging

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.cashsdk.CashSDK
import com.cashsdk.model.Entitlements
import com.cashsdk.model.PurchaseKind
import kotlinx.coroutines.launch

/**
 * The whole sample: configure the SDK, show live entitlement state, and drive one real
 * Google Play purchase. Everything the money-path test needs and nothing it doesn't.
 *
 * Buy → the SDK opens Play's billing dialog → verifies the token with CashSDK → the server
 * grants the entitlement → `entitlementUpdates` emits and the UI flips to "active". A license
 * tester is never charged.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // One-time init. apiBase points at staging/prod; the publishable key is the app's csk_pk_.
        if (!CashSDK.isConfigured) {
            CashSDK.configure(this, BuildConfig.CASHSDK_PK, BuildConfig.CASHSDK_API_BASE)
        }
        // Bind a stable test identity so the purchase is attributed to a user (drives RTDN
        // attribution + cross-device restore). A real app would use its own user id.
        CashSDK.shared.identify("sample-tester")

        setContent { SampleScreen() }
    }

    @Composable
    private fun SampleScreen() {
        val entitlements by CashSDK.shared.entitlementUpdates.collectAsStateWithLifecycle(
            initialValue = CashSDK.shared.entitlements,
        )
        var status by remember { mutableStateOf("Ready. Product: ${BuildConfig.CASHSDK_PRODUCT_ID}") }
        var busy by remember { mutableStateOf(false) }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("CashSDK · Play money-path test", style = MaterialTheme.typography.titleLarge)
                Text(
                    "app: ${BuildConfig.APPLICATION_ID}\napi: ${BuildConfig.CASHSDK_API_BASE}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )

                EntitlementCard(entitlements)

                Button(
                    onClick = {
                        busy = true
                        status = "Opening Play billing…"
                        lifecycleScope.launch {
                            status =
                                try {
                                    val result = CashSDK.shared.purchase(
                                        this@MainActivity,
                                        BuildConfig.CASHSDK_PRODUCT_ID,
                                        PurchaseKind.SUBSCRIPTION,
                                    )
                                    "Purchase verified — active entitlements: ${result.active.size}"
                                } catch (e: Throwable) {
                                    "Purchase failed: ${e.message}"
                                } finally {
                                    busy = false
                                }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Working…" else "Buy subscription") }

                OutlinedButton(
                    onClick = {
                        busy = true
                        status = "Restoring…"
                        lifecycleScope.launch {
                            status =
                                try {
                                    val r = CashSDK.shared.restore()
                                    "Restored — active entitlements: ${r.active.size}"
                                } catch (e: Throwable) {
                                    "Restore failed: ${e.message}"
                                } finally {
                                    busy = false
                                }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restore purchases") }

                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun EntitlementCard(e: Entitlements) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (e.hasActiveEntitlement) "✓ Entitled" else "— No active entitlement",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("tier: ${e.tier}", style = MaterialTheme.typography.bodySmall)
                if (e.active.isNotEmpty()) {
                    Text(
                        "active: " + e.active.joinToString { it.identifier },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
