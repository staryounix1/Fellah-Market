package com.example.feature.prices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.domain.PriceAnalytics
import com.example.core.l10n.LocalL10n
import com.example.core.ui.*
import com.example.ui.theme.*
import java.time.LocalDate

@Composable
fun MarketPricesScreen(
    state: AppUiState
) {
    val l10n = LocalL10n.current
    val today = remember { LocalDate.now().toEpochDay() }
    var selectedRegionId by remember {
        mutableStateOf(state.regions.firstOrNull { it.isDefault }?.id ?: "reg_souss_massa")
    }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var expandedProductId by remember { mutableStateOf<String?>("prod_tomato") }

    val filteredProducts = remember(state.products, selectedCategoryId) {
        if (selectedCategoryId == null) state.products
        else state.products.filter { it.categoryId == selectedCategoryId }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(WarmParchment),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(l10n.get(R.string.prices_title), style = MaterialTheme.typography.headlineSmall)
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.regions.forEach { reg ->
                    FilterChip(
                        selected = selectedRegionId == reg.id,
                        onClick = { selectedRegionId = reg.id },
                        label = { Text(l10n.localizedName(reg.nameDarija, reg.nameAr, reg.nameFr)) }
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategoryId == null,
                    onClick = { selectedCategoryId = null },
                    label = { Text(l10n.get(R.string.all_categories)) }
                )
                state.categories.forEach { cat ->
                    FilterChip(
                        selected = selectedCategoryId == cat.id,
                        onClick = { selectedCategoryId = if (selectedCategoryId == cat.id) null else cat.id },
                        label = { Text(l10n.localizedName(cat.nameDarija, cat.nameAr, cat.nameFr)) }
                    )
                }
            }
        }

        items(filteredProducts, key = { it.id }) { prod ->
            val preferredUnit = prod.allowedUnitsCsv.split(",").firstOrNull()?.trim() ?: "kg"
            val summary = remember(prod.id, selectedRegionId, state.deals, state.offers, state.referencePrices) {
                PriceAnalytics.computeMarketSummary(
                    productId = prod.id,
                    regionId = selectedRegionId,
                    deals = state.deals,
                    offers = state.offers,
                    referencePrices = state.referencePrices,
                    todayEpochDay = today,
                    preferredUnit = preferredUnit
                )
            }
            val prodName = l10n.localizedName(prod.nameDarija, prod.nameAr, prod.nameFr)
            val isExpanded = expandedProductId == prod.id

            Card(
                colors = CardDefaults.cardColors(containerColor = CrispSurface),
                border = BorderStroke(if (isExpanded) 2.dp else 1.dp, if (isExpanded) ForestGreen else SubtleBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedProductId = if (isExpanded) null else prod.id }
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.TrendingUp, contentDescription = null, tint = ForestGreen)
                            Text(prodName, style = MaterialTheme.typography.titleLarge)
                        }
                        if (summary.averagePrice != null) {
                            Text(
                                text = "${formatMad(summary.averagePrice)} ${l10n.get(R.string.currency_mad)}/${l10n.unitLabel(summary.unit)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = ForestGreen,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(l10n.get(R.string.not_enough_data), style = MaterialTheme.typography.labelMedium, color = MutedMossText)
                        }
                    }

                    if (summary.wholesaleReferencePrice != null) {
                        Text(
                            text = "${l10n.get(R.string.wholesale_ref_price)}: ${formatMad(summary.wholesaleReferencePrice)} ${l10n.get(R.string.currency_mad)}/${l10n.unitLabel(summary.unit)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedMossText
                        )
                    }

                    if (isExpanded) {
                        if (summary.trendPoints.size >= 2) {
                            Text(l10n.get(R.string.last_30_days), style = MaterialTheme.typography.labelMedium, color = ForestGreen)
                            PriceTrendLineChart(
                                points = summary.trendPoints,
                                unitLabel = l10n.unitLabel(summary.unit),
                                currencyLabel = l10n.get(R.string.currency_mad)
                            )
                        } else {
                            Text(l10n.get(R.string.not_enough_data), color = MutedMossText)
                        }
                    }
                }
            }
        }
    }
}
