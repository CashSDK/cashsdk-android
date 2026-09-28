@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cashsdk.paywall

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.cashsdk.billing.ProductPrice
import com.cashsdk.model.PaywallConfig
import com.cashsdk.model.PaywallLegal
import com.cashsdk.model.PaywallProduct
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Containers & template bodies for PaywallScreen (same package → module-internal).
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun PaywallContainer(
    presentation: String,
    background: Color,
    shape: RoundedCornerShape,
    onScrimClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    when (presentation) {
        "modal", "sheet" -> {
            // Scrim + card floating over the host (the Activity window is translucent).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onScrimClick() },
                contentAlignment = if (presentation == "sheet") Alignment.BottomCenter else Alignment.Center,
            ) {
                Surface(
                    color = background,
                    shape = shape,
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .padding(if (presentation == "sheet") 0.dp else 16.dp),
                ) { content() }
            }
        }
        else -> Surface( // fullscreen
            color = background,
            modifier = Modifier.fillMaxSize(),
        ) { content() }
    }
}

@Composable
internal fun PlanPickerBody(
    config: PaywallConfig,
    prices: Map<String, ProductPrice>,
    locale: Locale,
    accent: Color,
    shape: RoundedCornerShape,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        config.products.forEach { product ->
            PlanCard(
                product = product,
                price = prices[product.productId],
                badge = resolveBadge(product.badge, config.copy, locale),
                selected = product.productId == selected,
                accent = accent,
                shape = shape,
                onClick = { onSelect(product.productId) },
            )
        }
    }
}

@Composable
private fun PlanCard(
    product: PaywallProduct,
    price: ProductPrice?,
    badge: String?,
    selected: Boolean,
    accent: Color,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (selected) 0.35f else 0.15f),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = price?.title ?: product.productId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                badge?.let {
                    Text(text = it, style = MaterialTheme.typography.labelSmall, color = accent)
                }
                price?.introText()?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                text = price?.priceText() ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun FeatureListBody(
    config: PaywallConfig,
    prices: Map<String, ProductPrice>,
    locale: Locale,
    accent: Color,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Feature checklist: copy keys feature_1..feature_N (stop at the first gap).
        var i = 1
        val features = buildList {
            while (i <= MAX_FEATURES) {
                val line = resolveCopy(config.copy, "feature_$i", locale) ?: break
                add(line)
                i++
            }
        }
        features.forEach { FeatureRow(text = it, accent = accent) }

        // Price row for the primary product.
        val primary = config.products.firstOrNull { it.role == "primary" } ?: config.products.firstOrNull()
        val price = primary?.let { prices[it.productId] }
        if (primary != null) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = price?.title ?: primary.productId,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = price?.priceText() ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            price?.introText()?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FeatureRow(text: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "✓", color = accent, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun CenteredHeroBody(primaryPrice: ProductPrice?) {
    // Hero art slot is host-provided in a full build; the scaffold shows the resolved price.
    primaryPrice?.priceText()?.takeIf { it.isNotBlank() }?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    primaryPrice?.introText()?.let {
        Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun LegalFooter(legal: PaywallLegal, context: android.content.Context) {
    if (legal.termsUrl == null && legal.privacyUrl == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        legal.termsUrl?.let { url ->
            Text(
                text = "Terms",
                style = MaterialTheme.typography.labelSmall,
                textDecoration = TextDecoration.Underline,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { openUrl(context, url) },
            )
        }
        if (legal.termsUrl != null && legal.privacyUrl != null) {
            Text(
                text = "  ·  ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        legal.privacyUrl?.let { url ->
            Text(
                text = "Privacy",
                style = MaterialTheme.typography.labelSmall,
                textDecoration = TextDecoration.Underline,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { openUrl(context, url) },
            )
        }
    }
}
