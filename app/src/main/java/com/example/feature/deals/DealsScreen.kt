package com.example.feature.deals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.data.*
import com.example.core.domain.DealStateMachine
import com.example.core.l10n.LocalL10n
import com.example.core.ui.*
import com.example.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DealsScreen(
    state: AppUiState,
    onAcceptDeal: (DealEntity) -> Unit,
    onRejectDeal: (DealEntity) -> Unit,
    onCounterDeal: (DealEntity, Double) -> Unit,
    onConfirmCompletion: (DealEntity) -> Unit,
    onRatePartner: (DealEntity, Int, String) -> Unit,
    onDismissError: () -> Unit = {}
) {
    val l10n = LocalL10n.current
    val context = LocalContext.current
    val user = state.currentUser ?: return
    val myDeals = remember(state.deals, user.id, user.role) {
        if (user.role == UserRole.ADMIN) state.deals
        else state.deals.filter { it.farmerId == user.id || it.buyerId == user.id }
    }

    var counterTarget by remember { mutableStateOf<DealEntity?>(null) }
    var ratingTarget by remember { mutableStateOf<DealEntity?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(WarmParchment),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(l10n.get(R.string.deals_title), style = MaterialTheme.typography.headlineSmall)
                Text(l10n.get(R.string.cash_on_delivery_note), style = MaterialTheme.typography.bodyMedium, color = MutedMossText)
            }
        }

        if (state.dealActionError != null) {
            item {
                Surface(
                    color = ErrorContainerLight,
                    contentColor = ErrorCrimson,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(state.dealActionError, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = onDismissError) {
                            Icon(Icons.Rounded.Close, contentDescription = l10n.get(R.string.close_btn))
                        }
                    }
                }
            }
        }

        if (myDeals.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Rounded.Handshake,
                    title = l10n.get(R.string.deals_title),
                    subtitle = l10n.get(R.string.empty_offers_desc)
                )
            }
        } else {
            items(myDeals, key = { it.id }) { deal ->
                val product = state.products.firstOrNull { it.id == deal.productId }
                val prodName = product?.let { l10n.localizedName(it.nameDarija, it.nameAr, it.nameFr) } ?: deal.productId
                val partnerId = if (user.id == deal.farmerId) deal.buyerId else deal.farmerId
                val partner = state.profiles.firstOrNull { it.id == partnerId }
                val history = state.dealHistory.filter { it.dealId == deal.id }
                val alreadyRated = state.ratings.any { it.dealId == deal.id && it.fromUserId == user.id }
                val phoneUnlocked = DealStateMachine.isPhoneRevealed(deal.status)
                val canNegotiate = DealStateMachine.canActOnNegotiation(deal, user.role)

                Card(
                    colors = CardDefaults.cardColors(containerColor = CrispSurface),
                    border = BorderStroke(1.dp, SubtleBorder),
                    modifier = Modifier.fillMaxWidth().testTag("deal_card_${deal.id}")
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(prodName, style = MaterialTheme.typography.titleLarge)
                            DealStatusPill(deal.status)
                        }

                        val displayPrice = deal.agreedPrice ?: deal.proposedPrice
                        Text(
                            text = "${formatMad(deal.quantity)} ${l10n.unitLabel(deal.unit)} • ${formatMad(displayPrice)} ${l10n.get(R.string.currency_mad)}/${l10n.unitLabel(deal.unit)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = ForestGreen,
                            fontWeight = FontWeight.Bold
                        )

                        if (partner != null) {
                            Text("${partner.fullName} (${partner.city})", style = MaterialTheme.typography.bodyMedium, color = MutedMossText)
                        }

                        if (deal.note.isNotBlank()) {
                            Text(deal.note, style = MaterialTheme.typography.bodyMedium)
                        }

                        if (deal.syncState == SyncState.PENDING_SYNC) {
                            StatusPill(l10n.get(R.string.pending_sync_badge), SaffronContainer, OnSaffronContainer)
                        }

                        // Negotiation actions
                        if (canNegotiate) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onAcceptDeal(deal) },
                                    modifier = Modifier.testTag("accept_deal_${deal.id}")
                                ) {
                                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(l10n.get(R.string.accept_btn))
                                }
                                OutlinedButton(onClick = { counterTarget = deal }) {
                                    Text(l10n.get(R.string.counter_offer_btn))
                                }
                                OutlinedButton(
                                    onClick = { onRejectDeal(deal) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorCrimson)
                                ) {
                                    Text(l10n.get(R.string.reject_btn))
                                }
                            }
                        }

                        // Contact unlocked ONLY after acceptance
                        if (phoneUnlocked && partner != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        openWhatsApp(context, partner.phone, "${l10n.get(R.string.app_name)}: $prodName (${formatMad(displayPrice)} MAD)")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = WhatsappGreen),
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Icon(Icons.Rounded.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(l10n.get(R.string.contact_whatsapp))
                                }
                                OutlinedButton(
                                    onClick = { openPhoneDialer(context, partner.phone) },
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(l10n.get(R.string.contact_call))
                                }
                            }
                        } else if (deal.status in setOf(DealStatus.REQUESTED, DealStatus.COUNTERED)) {
                            Text(
                                text = l10n.get(R.string.phone_locked_hint),
                                style = MaterialTheme.typography.labelMedium,
                                color = HarvestTerracotta
                            )
                        }

                        // Completion confirmation by both sides
                        if (deal.status == DealStatus.ACCEPTED && user.role != UserRole.ADMIN) {
                            val iConfirmed = (user.role == UserRole.FARMER && deal.farmerConfirmedCompletion) ||
                                (user.role == UserRole.BUYER && deal.buyerConfirmedCompletion)
                            if (!iConfirmed) {
                                Button(
                                    onClick = { onConfirmCompletion(deal) },
                                    colors = ButtonDefaults.buttonColors(containerColor = HarvestTerracotta),
                                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("confirm_completion_${deal.id}")
                                ) {
                                    Icon(Icons.Rounded.VerifiedUser, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(l10n.get(R.string.confirm_completion_btn))
                                }
                            } else {
                                Text(l10n.get(R.string.waiting_other_confirm), color = SaffronGold, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // Rating after completion
                        if (deal.status == DealStatus.COMPLETED && !alreadyRated && user.role != UserRole.ADMIN) {
                            Button(
                                onClick = { ratingTarget = deal },
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Icon(Icons.Rounded.Star, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(l10n.get(R.string.rate_partner_btn))
                            }
                        }

                        // Status history timeline
                        if (history.isNotEmpty()) {
                            HorizontalDivider(color = SubtleBorder)
                            Text(l10n.get(R.string.deal_history_title), style = MaterialTheme.typography.labelMedium, color = MutedMossText)
                            history.forEach { h ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("• ${h.status}: ${h.note}", style = MaterialTheme.typography.bodyMedium)
                                    Text("${formatMad(h.priceAtStep)} MAD", style = MaterialTheme.typography.labelMedium, color = ForestGreen)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (counterTarget != null) {
        val target = counterTarget!!
        var newPrice by remember { mutableStateOf(formatMad(target.proposedPrice)) }
        AlertDialog(
            onDismissRequest = { counterTarget = null },
            title = { Text(l10n.get(R.string.counter_offer_btn)) },
            text = {
                OutlinedTextField(
                    value = newPrice,
                    onValueChange = { newPrice = it },
                    label = { Text(l10n.get(R.string.your_offered_price)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val p = newPrice.replace(',', '.').toDoubleOrNull() ?: return@Button
                    if (p > 0) {
                        onCounterDeal(target, p)
                        counterTarget = null
                    }
                }) { Text(l10n.get(R.string.confirm_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { counterTarget = null }) { Text(l10n.get(R.string.cancel_btn)) }
            }
        )
    }

    if (ratingTarget != null) {
        val target = ratingTarget!!
        var stars by remember { mutableStateOf(5) }
        var comment by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { ratingTarget = null },
            title = { Text(l10n.get(R.string.rating_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (s in 1..5) {
                            IconButton(onClick = { stars = s }) {
                                Icon(
                                    imageVector = if (s <= stars) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                    contentDescription = "$s",
                                    tint = SaffronGold,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text(l10n.get(R.string.rating_comment_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onRatePartner(target, stars, comment)
                    ratingTarget = null
                }) { Text(l10n.get(R.string.confirm_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { ratingTarget = null }) { Text(l10n.get(R.string.cancel_btn)) }
            }
        )
    }
}

@Composable
fun DealStatusPill(status: String) {
    val l10n = LocalL10n.current
    val (label, bg, fg) = when (status) {
        DealStatus.REQUESTED -> Triple(l10n.get(R.string.deal_requested), SaffronContainer, OnSaffronContainer)
        DealStatus.COUNTERED -> Triple(l10n.get(R.string.deal_countered), WarmApricotContainer, OnApricotContainer)
        DealStatus.ACCEPTED -> Triple(l10n.get(R.string.deal_accepted), SoftSageContainer, OnSageContainer)
        DealStatus.COMPLETED -> Triple(l10n.get(R.string.deal_completed), ForestGreen, CrispSurface)
        else -> Triple(l10n.get(R.string.deal_cancelled), ErrorContainerLight, ErrorCrimson)
    }
    StatusPill(label, bg, fg)
}
