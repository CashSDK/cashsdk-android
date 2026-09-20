@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cashsdk.paywall

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cashsdk.CashSDKClient
import com.cashsdk.CashSDKError
import com.cashsdk.billing.ProductPrice
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The paywall renderer: a pure Compose function fed by the validated [PaywallConfig]
 * (docs/09-PAYWALLS.md §2/§3). Supports the three v1 templates — `centered_hero_v1`,
 * `plan_picker_v1`, `feature_list_v1` — reading copy (locale fallback: exact → language →
 * `en` → skip), style tokens, product roles, CTA, restore, and legal footer. Live localized
 * prices come from Google Play via [CashSDKClient.paywallPrice].
 *
 * An unknown template falls back to a single-CTA hero rather than crashing (FR-6.7).
 *
 * The template-body subviews live in `PaywallComponents.kt` and the copy/color/URL helpers
 * in `PaywallHelpers.kt` — same package, so they stay module-internal.
 */
@Composable
internal fun PaywallScreen(
    presentation: PaywallPresentation,
    client: CashSDKClient,
    onDismiss: () -> Unit,
) {
    val config = presentation.config
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val dark = when (config.style.darkMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        val accent = parseColor(config.style.accentColor) ?: MaterialTheme.colorScheme.primary
        val background = parseColor(config.style.background) ?: MaterialTheme.colorScheme.surface
        val shape = RoundedCornerShape(config.style.cornerRadius.dp)

        // Live Play prices, filled in as they resolve.
        val prices = remember { mutableStateMapOf<String, ProductPrice>() }
        LaunchedEffect(Unit) {
            config.products.forEach { p ->
                runCatching { client.paywallPrice(p.productId) }.getOrNull()?.let { prices[p.productId] = it }
            }
        }

        var selected by rememberSaveable {
            mutableStateOf(
                config.products.firstOrNull { it.role == "primary" }?.productId
                    ?: config.products.firstOrNull()?.productId,
            )
        }
        var busy by remember { mutableStateOf(false) }
        var errorText by remember { mutableStateOf<String?>(null) }

        fun buy(productId: String?) {
            val price = productId?.let { prices[it] }
            if (activity == null || productId == null || price == null) {
                errorText = "This product is unavailable right now."
                return
            }
            scope.launch {
                busy = true
                errorText = null
                try {
                    client.paywallPurchase(activity, price)
                    // `variant` matters most on the CONVERSION event — it is what attributes
                    // revenue to an experiment arm server-side.
                    client.track(
                        "purchase_success",
                        placement = presentation.placement,
                        product = productId,
                        variant = presentation.variantId,
                    )
                    onDismiss()
                } catch (e: CashSDKError.PurchaseCancelled) {
                    // User dismissed the Play sheet — stay on the paywall.
                } catch (e: CashSDKError.PurchasePending) {
                    errorText = "Your purchase is pending approval."
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    errorText = purchaseErrorText(e)
                    client.track(
                        "purchase_failed",
                        placement = presentation.placement,
                        product = productId,
                        variant = presentation.variantId,
                    )
                } finally {
                    busy = false
                }
            }
        }

        fun restore() {
            scope.launch {
                busy = true
                errorText = null
                try {
                    client.restore()
                    onDismiss()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    errorText = purchaseErrorText(e)
                } finally {
                    busy = false
                }
            }
        }

        val dismissable = config.settings.showCloseButton
        PaywallContainer(
            presentation = config.presentation,
            background = background,
            shape = shape,
            onScrimClick = { if (dismissable) onDismiss() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (dismissable) {
                    Box(Modifier.fillMaxWidth()) {
                        Text(
                            text = "✕",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .clickable(enabled = !busy) { onDismiss() }
                                .padding(4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                resolveCopy(config.copy, "title", locale)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                resolveCopy(config.copy, "subtitle", locale)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(24.dp))

                // ── Template body ──────────────────────────────────────────────
                when (config.template) {
                    "plan_picker_v1" -> PlanPickerBody(
                        config = config,
                        prices = prices,
                        locale = locale,
                        accent = accent,
                        shape = shape,
                        selected = selected,
                        onSelect = { selected = it },
                    )
                    "feature_list_v1" -> FeatureListBody(
                        config = config,
                        prices = prices,
                        locale = locale,
                        accent = accent,
                    )
                    else -> CenteredHeroBody( // centered_hero_v1 + unknown-template fallback
                        primaryPrice = prices[selected],
                    )
                }

                Spacer(Modifier.height(20.dp))

                errorText?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // ── Primary CTA ────────────────────────────────────────────────
                Button(
                    onClick = { buy(selected) },
                    enabled = !busy && prices[selected] != null,
                    shape = shape,
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            text = resolveCopy(config.copy, "cta", locale) ?: "Continue",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                if (prices[selected]?.phases?.any { it.recurrenceMode == 1 } == true) {
                    Text(
                        text = "Renews automatically. Cancel anytime in Google Play.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                if (config.settings.showRestore) {
                    TextButton(onClick = { restore() }, enabled = !busy) {
                        Text("Restore purchases", color = accent)
                    }
                }

                LegalFooter(legal = config.settings.legal, context = context)
            }
        }
    }
}
