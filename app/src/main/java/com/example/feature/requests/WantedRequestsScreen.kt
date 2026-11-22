package com.example.feature.requests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.data.*
import com.example.core.l10n.LocalL10n
import com.example.core.ui.*
import com.example.ui.theme.*
import java.time.LocalDate

@Composable
fun WantedRequestsScreen(
    state: AppUiState,
    onPostWanted: (ProductEntity, String, String, Double, String, Double, Long, String) -> Unit,
    onRespondToWanted: (BuyRequestEntity, Double, String, String) -> Unit
) {
    val l10n = LocalL10n.current
    val user = state.currentUser ?: return
    var showCreateWanted by remember { mutableStateOf(false) }
    var respondingTo by remember { mutableStateOf<BuyRequestEntity?>(null) }
    var snackMsg by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(WarmParchment)) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(l10n.get(R.string.wanted_title), style = MaterialTheme.typography.headlineSmall)
            }
            if (snackMsg != null) {
                item {
                    Surface(color = SoftSageContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                        Text(snackMsg!!, color = OnSageContainer, modifier = Modifier.padding(12.dp))
                    }
                }
            }

            if (state.buyRequests.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Rounded.Campaign,
                        title = l10n.get(R.string.empty_wanted_title)
                    )
                }
            } else {
                items(state.buyRequests, key = { it.id }) { req ->
                    val product = state.products.firstOrNull { it.id == req.productId }
                    val prodName = product?.let { l10n.localizedName(it.nameDarija, it.nameAr, it.nameFr) } ?: req.productId
                    val buyer = state.profiles.firstOrNull { it.id == req.buyerId }
                    val buyerBiz = state.buyerDetails.firstOrNull { it.userId == req.buyerId }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = CrispSurface),
                        border = BorderStroke(1.dp, SubtleBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(prodName, style = MaterialTheme.typography.titleLarge)
                                if (req.syncState == SyncState.PENDING_SYNC) {
                                    StatusPill(l10n.get(R.string.pending_sync_badge), SaffronContainer, OnSaffronContainer)
                                }
                            }
                            Text(
                                text = "${formatMad(req.quantity)} ${l10n.unitLabel(req.unit)} • Max ${formatMad(req.maxPrice)} ${l10n.get(R.string.currency_mad)}/${l10n.unitLabel(req.unit)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = HarvestTerracotta,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${req.city} • ${formatEpochDay(req.neededDateEpochDay)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MutedMossText
                            )
                            if (buyerBiz != null || buyer != null) {
                                Text(
                                    text = buyerBiz?.businessName ?: buyer?.fullName.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ForestGreen
                                )
                            }
                            if (req.notes.isNotBlank()) {
                                Text(req.notes, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (user.role == UserRole.FARMER && user.verificationStatus == VerificationStatus.VERIFIED) {
                                Button(
                                    onClick = { respondingTo = req },
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Icon(Icons.Rounded.LocalOffer, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(l10n.get(R.string.respond_wanted_btn))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (user.role == UserRole.BUYER && user.verificationStatus == VerificationStatus.VERIFIED) {
            ExtendedFloatingActionButton(
                onClick = { showCreateWanted = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(l10n.get(R.string.post_wanted_btn)) },
                containerColor = HarvestTerracotta,
                contentColor = CrispSurface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("post_wanted_fab")
            )
        }
    }

    if (showCreateWanted) {
        PostWantedDialog(
            state = state,
            onDismiss = { showCreateWanted = false },
            onSubmit = { prod, city, regionId, qty, unit, maxPrice, day, notes ->
                onPostWanted(prod, city, regionId, qty, unit, maxPrice, day, notes)
                showCreateWanted = false
            }
        )
    }

    if (respondingTo != null) {
        val target = respondingTo!!
        var priceText by remember { mutableStateOf(formatMad(target.maxPrice)) }
        var deliveryType by remember { mutableStateOf("pickup") }
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { respondingTo = null },
            title = { Text(l10n.get(R.string.respond_wanted_btn)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = { Text(l10n.get(R.string.your_offered_price)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = deliveryType == "pickup", onClick = { deliveryType = "pickup" }, label = { Text(l10n.get(R.string.pickup_farm)) })
                        FilterChip(selected = deliveryType == "delivery", onClick = { deliveryType = "delivery" }, label = { Text(l10n.get(R.string.delivery_buyer)) })
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(l10n.get(R.string.request_note_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val p = priceText.replace(',', '.').toDoubleOrNull() ?: return@Button
                    if (p > 0) {
                        onRespondToWanted(target, p, deliveryType, note)
                        respondingTo = null
                        snackMsg = l10n.get(R.string.deal_requested)
                    }
                }) { Text(l10n.get(R.string.confirm_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { respondingTo = null }) { Text(l10n.get(R.string.cancel_btn)) }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostWantedDialog(
    state: AppUiState,
    onDismiss: () -> Unit,
    onSubmit: (ProductEntity, String, String, Double, String, Double, Long, String) -> Unit
) {
    val l10n = LocalL10n.current
    val user = state.currentUser ?: return
    var selectedProduct by remember { mutableStateOf(state.products.firstOrNull()) }
    val units = remember(selectedProduct) {
        selectedProduct?.allowedUnitsCsv?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("kg")
    }
    var selectedUnit by remember(selectedProduct) { mutableStateOf(units.first()) }
    var qtyText by remember { mutableStateOf("50") }
    var maxPriceText by remember { mutableStateOf("80") }
    var notes by remember { mutableStateOf("") }
    val today = remember { LocalDate.now().toEpochDay() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(l10n.get(R.string.post_wanted_btn)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.products.take(10).forEach { prod ->
                        FilterChip(
                            selected = selectedProduct?.id == prod.id,
                            onClick = { selectedProduct = prod },
                            label = { Text(l10n.localizedName(prod.nameDarija, prod.nameAr, prod.nameFr)) }
                        )
                    }
                }
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = it },
                    label = { Text(l10n.get(R.string.quantity_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    units.forEach { u ->
                        FilterChip(selected = selectedUnit == u, onClick = { selectedUnit = u }, label = { Text(l10n.unitLabel(u)) })
                    }
                }
                OutlinedTextField(
                    value = maxPriceText,
                    onValueChange = { maxPriceText = it },
                    label = { Text(l10n.get(R.string.max_budget_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(l10n.get(R.string.request_note_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val prod = selectedProduct ?: return@Button
                val q = qtyText.replace(',', '.').toDoubleOrNull() ?: return@Button
                val p = maxPriceText.replace(',', '.').toDoubleOrNull() ?: return@Button
                if (q > 0 && p > 0) {
                    onSubmit(prod, user.city, user.regionId, q, selectedUnit, p, today + 2, notes)
                }
            }) { Text(l10n.get(R.string.confirm_btn)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(l10n.get(R.string.cancel_btn)) }
        }
    )
}
