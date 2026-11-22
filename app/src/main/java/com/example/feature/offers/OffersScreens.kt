package com.example.feature.offers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.data.*
import com.example.core.domain.DynamicFormSchemaCodec
import com.example.core.domain.ProductMarketSummary
import com.example.core.l10n.LocalL10n
import com.example.core.media.VoiceNoteManager
import com.example.core.media.VoiceUiState
import com.example.core.ui.*
import com.example.ui.theme.*
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyOffersScreen(
    state: AppUiState,
    onCreateOffer: () -> Unit,
    onEditOffer: (OfferEntity) -> Unit,
    onSetOfferStatus: (String, String) -> Unit,
    onDuplicateOffer: (OfferEntity) -> Unit,
    onOpenOfferDetail: (OfferEntity) -> Unit,
    onRetryMediaUpload: (OfferEntity) -> Unit = {}
) {
    val l10n = LocalL10n.current
    val user = state.currentUser ?: return
    val myOffers = remember(state.offers, user.id) { state.offers.filter { it.farmerId == user.id } }

    Box(modifier = Modifier.fillMaxSize().background(WarmParchment)) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (user.verificationStatus != VerificationStatus.VERIFIED) {
                item { VerificationBanner(user) }
            }

            if (myOffers.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Rounded.Inventory2,
                        title = l10n.get(R.string.empty_offers_title),
                        subtitle = l10n.get(R.string.create_offer_title)
                    )
                }
            } else {
                items(myOffers, key = { it.id }) { offer ->
                    val product = state.products.firstOrNull { it.id == offer.productId }
                    val productName = product?.let { l10n.localizedName(it.nameDarija, it.nameAr, it.nameFr) } ?: offer.productId
                    val incomingCount = state.deals.count {
                        it.offerId == offer.id && it.status in setOf(DealStatus.REQUESTED, DealStatus.COUNTERED)
                    }
                    val firstPhoto = offer.photoUrisCsv.split(",").firstOrNull()?.takeIf { it.isNotBlank() } ?: "drawable:hero"

                    Card(
                        colors = CardDefaults.cardColors(containerColor = CrispSurface),
                        border = BorderStroke(1.dp, SubtleBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenOfferDetail(offer) }
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                OfferPhotoView(
                                    photoToken = firstPhoto,
                                    contentDescription = productName,
                                    modifier = Modifier
                                        .size(76.dp)
                                        .clip(MaterialTheme.shapes.small)
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(productName, style = MaterialTheme.typography.titleLarge)
                                        OfferStatusBadge(offer.status)
                                    }
                                    Text(
                                        text = "${formatMad(offer.askingPrice)} ${l10n.get(R.string.currency_mad)} / ${l10n.unitLabel(offer.unit)} • ${formatMad(offer.quantity)} ${l10n.unitLabel(offer.unit)}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = ForestGreen
                                    )
                                    Text(
                                        text = "${offer.city} • ${formatEpochDay(offer.readyDateEpochDay)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MutedMossText
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (offer.syncState == SyncState.PENDING_SYNC) {
                                    StatusPill(l10n.get(R.string.pending_sync_badge), SaffronContainer, OnSaffronContainer)
                                }
                                if (offer.mediaStatus == MediaStatus.UPLOADING) {
                                    StatusPill(l10n.get(R.string.media_uploading_badge), SaffronContainer, OnSaffronContainer)
                                } else if (offer.mediaStatus == MediaStatus.FAILED) {
                                    StatusPill(l10n.get(R.string.media_failed_badge), ErrorContainerLight, ErrorCrimson)
                                    TextButton(onClick = { onRetryMediaUpload(offer) }) {
                                        Text(l10n.get(R.string.retry_btn), color = ErrorCrimson)
                                    }
                                }
                                if (incomingCount > 0) {
                                    StatusPill(
                                        l10n.format(R.string.incoming_requests_count, incomingCount),
                                        WarmApricotContainer,
                                        OnApricotContainer
                                    )
                                }
                            }

                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { onEditOffer(offer) }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(l10n.get(R.string.action_edit))
                                }
                                if (offer.status == OfferStatus.ACTIVE) {
                                    OutlinedButton(onClick = { onSetOfferStatus(offer.id, OfferStatus.PAUSED) }) {
                                        Text(l10n.get(R.string.action_pause))
                                    }
                                    OutlinedButton(onClick = { onSetOfferStatus(offer.id, OfferStatus.SOLD) }) {
                                        Text(l10n.get(R.string.action_mark_sold))
                                    }
                                } else if (offer.status == OfferStatus.PAUSED) {
                                    OutlinedButton(onClick = { onSetOfferStatus(offer.id, OfferStatus.ACTIVE) }) {
                                        Text(l10n.get(R.string.action_activate))
                                    }
                                }
                                OutlinedButton(onClick = { onDuplicateOffer(offer) }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(l10n.get(R.string.action_duplicate))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (user.verificationStatus == VerificationStatus.VERIFIED) {
            ExtendedFloatingActionButton(
                onClick = onCreateOffer,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(l10n.get(R.string.create_offer_btn)) },
                containerColor = ForestGreen,
                contentColor = CrispSurface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("create_offer_fab")
            )
        }
    }
}

@Composable
fun OfferStatusBadge(status: String) {
    val l10n = LocalL10n.current
    val (label, bg, fg) = when (status) {
        OfferStatus.ACTIVE -> Triple(l10n.get(R.string.offer_status_active), SoftSageContainer, OnSageContainer)
        OfferStatus.RESERVED -> Triple(l10n.get(R.string.offer_status_reserved), SaffronContainer, OnSaffronContainer)
        OfferStatus.SOLD -> Triple(l10n.get(R.string.offer_status_sold), SoftFieldSurface, MutedMossText)
        OfferStatus.PAUSED -> Triple(l10n.get(R.string.offer_status_paused), WarmApricotContainer, OnApricotContainer)
        else -> Triple(l10n.get(R.string.offer_status_expired), ErrorContainerLight, ErrorCrimson)
    }
    StatusPill(label, bg, fg)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfferDetailScreen(
    offer: OfferEntity,
    state: AppUiState,
    voiceState: VoiceUiState,
    voiceManager: VoiceNoteManager,
    marketSummary: ProductMarketSummary,
    onSendDealRequest: (Double, Double, String, Long, String) -> Unit,
    onReportOffer: (String) -> Unit,
    onPlayVoice: (String) -> Unit = { voiceManager.togglePlayback(it) },
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val l10n = LocalL10n.current
    val currentUser = state.currentUser ?: return
    val product = state.products.firstOrNull { it.id == offer.productId }
    val productName = product?.let { l10n.localizedName(it.nameDarija, it.nameAr, it.nameFr) } ?: offer.productId
    val farmer = state.profiles.firstOrNull { it.id == offer.farmerId }
    val farmerDetails = state.farmerDetails.firstOrNull { it.userId == offer.farmerId }
    val photos = remember(offer.photoUrisCsv) {
        offer.photoUrisCsv.split(",").filter { it.isNotBlank() }.ifEmpty { listOf("drawable:hero") }
    }
    val schema = remember(product) { DynamicFormSchemaCodec.parseSchema(product?.attributeSchemaJson.orEmpty()) }
    val attrValues = remember(offer.attributesJson) { DynamicFormSchemaCodec.parseValues(offer.attributesJson) }

    var showRequestDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var feedbackBanner by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(l10n.get(R.string.offer_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = l10n.get(R.string.back_btn))
                    }
                },
                actions = {
                    IconButton(onClick = { showReportDialog = true }) {
                        Icon(Icons.Rounded.Flag, contentDescription = l10n.get(R.string.report_offer_btn))
                    }
                }
            )
        },
        bottomBar = {
            if (currentUser.id != offer.farmerId && currentUser.role == UserRole.BUYER) {
                Surface(color = CrispSurface, shadowElevation = 8.dp) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        if (currentUser.verificationStatus == VerificationStatus.VERIFIED) {
                            Button(
                                onClick = { showRequestDialog = true },
                                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("send_purchase_request_button")
                            ) {
                                Icon(Icons.Rounded.Handshake, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(l10n.get(R.string.send_request_btn), style = MaterialTheme.typography.titleMedium)
                            }
                        } else {
                            Text(
                                text = l10n.get(R.string.verified_only_msg),
                                color = ErrorCrimson,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmParchment)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (feedbackBanner != null) {
                Surface(color = SoftSageContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Text(feedbackBanner!!, color = OnSageContainer, modifier = Modifier.padding(12.dp))
                }
            }

            // Photos strip
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                photos.forEach { photo ->
                    OfferPhotoView(
                        photoToken = photo,
                        contentDescription = productName,
                        modifier = Modifier
                            .width(290.dp)
                            .height(200.dp)
                            .clip(MaterialTheme.shapes.large)
                    )
                }
            }

            // Header price & quantity
            Card(
                colors = CardDefaults.cardColors(containerColor = CrispSurface),
                border = BorderStroke(1.dp, SubtleBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(productName, style = MaterialTheme.typography.headlineMedium)
                        OfferStatusBadge(offer.status)
                    }
                    Text(
                        text = "${formatMad(offer.askingPrice)} ${l10n.get(R.string.currency_mad)} / ${l10n.unitLabel(offer.unit)}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = ForestGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${l10n.get(R.string.quantity_label)}: ${formatMad(offer.quantity)} ${l10n.unitLabel(offer.unit)} • ${offer.city}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "${l10n.get(R.string.ready_date_label)}: ${formatEpochDay(offer.readyDateEpochDay)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedMossText
                    )
                }
            }

            // Voice note playback if present
            if (offer.voiceNotePath.isNotBlank()) {
                Surface(
                    color = SoftSageContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilledIconButton(onClick = { onPlayVoice(offer.voiceNotePath) }) {
                            Icon(
                                if (voiceState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = l10n.get(R.string.play_voice_btn)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(l10n.get(R.string.play_voice_btn), style = MaterialTheme.typography.titleMedium)
                            Text("${offer.voiceDurationSec}s", style = MaterialTheme.typography.bodyMedium, color = MutedMossText)
                        }
                    }
                }
            }

            // Farmer profile card (phone hidden until deal accepted!)
            if (farmer != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CrispSurface),
                    border = BorderStroke(1.dp, SubtleBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(50)).background(SoftSageContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Agriculture, contentDescription = null, tint = ForestGreen)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(farmer.fullName, style = MaterialTheme.typography.titleMedium)
                            if (!farmerDetails?.farmOrCoopName.isNullOrBlank()) {
                                Text(farmerDetails!!.farmOrCoopName, style = MaterialTheme.typography.bodyMedium, color = MutedMossText)
                            }
                            Text(l10n.get(R.string.phone_locked_hint), style = MaterialTheme.typography.labelMedium, color = HarvestTerracotta)
                        }
                        if (farmer.ratingCount > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Star, contentDescription = null, tint = SaffronGold, modifier = Modifier.size(20.dp))
                                Text(String.format(java.util.Locale.US, "%.1f", farmer.ratingAvg), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Dynamic attributes
            if (attrValues.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CrispSurface),
                    border = BorderStroke(1.dp, SubtleBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(l10n.get(R.string.attributes_label), style = MaterialTheme.typography.titleMedium)
                        attrValues.forEach { (k, v) ->
                            val fieldLabel = schema.firstOrNull { it.key == k }?.label(l10n.language) ?: k
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(fieldLabel, color = MutedMossText)
                                Text(v, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Price trend chart
            Card(
                colors = CardDefaults.cardColors(containerColor = CrispSurface),
                border = BorderStroke(1.dp, SubtleBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(l10n.get(R.string.price_trend_label), style = MaterialTheme.typography.titleMedium)
                    if (marketSummary.trendPoints.size >= 2) {
                        PriceTrendLineChart(
                            points = marketSummary.trendPoints,
                            unitLabel = l10n.unitLabel(offer.unit),
                            currencyLabel = l10n.get(R.string.currency_mad)
                        )
                    } else {
                        Text(l10n.get(R.string.not_enough_data), color = MutedMossText)
                    }
                }
            }
        }
    }

    if (showRequestDialog) {
        SendPurchaseRequestDialog(
            offer = offer,
            onDismiss = { showRequestDialog = false },
            onSubmit = { qty, price, delivery, day, note ->
                onSendDealRequest(qty, price, delivery, day, note)
                showRequestDialog = false
                feedbackBanner = l10n.get(R.string.deal_requested)
            }
        )
    }

    if (showReportDialog) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text(l10n.get(R.string.report_offer_btn)) },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(l10n.get(R.string.reject_reason_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (reason.isNotBlank()) {
                        onReportOffer(reason)
                        showReportDialog = false
                        feedbackBanner = l10n.get(R.string.report_sent_msg)
                    }
                }) { Text(l10n.get(R.string.confirm_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) { Text(l10n.get(R.string.cancel_btn)) }
            }
        )
    }
}

@Composable
private fun SendPurchaseRequestDialog(
    offer: OfferEntity,
    onDismiss: () -> Unit,
    onSubmit: (Double, Double, String, Long, String) -> Unit
) {
    val l10n = LocalL10n.current
    var qtyText by remember { mutableStateOf(formatMad(offer.quantity.coerceAtMost(20.0))) }
    var priceText by remember { mutableStateOf(formatMad(offer.askingPrice)) }
    var deliveryType by remember { mutableStateOf("pickup") }
    var note by remember { mutableStateOf("") }
    val today = remember { LocalDate.now().toEpochDay() }
    var preferredDay by remember { mutableStateOf(today + 1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(l10n.get(R.string.send_request_btn)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = it },
                    label = { Text("${l10n.get(R.string.quantity_label)} (${l10n.unitLabel(offer.unit)})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text(l10n.get(R.string.your_offered_price)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(l10n.get(R.string.delivery_type_label), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = deliveryType == "pickup",
                        onClick = { deliveryType = "pickup" },
                        label = { Text(l10n.get(R.string.pickup_farm)) }
                    )
                    FilterChip(
                        selected = deliveryType == "delivery",
                        onClick = { deliveryType = "delivery" },
                        label = { Text(l10n.get(R.string.delivery_buyer)) }
                    )
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
                val q = qtyText.replace(',', '.').toDoubleOrNull() ?: return@Button
                val p = priceText.replace(',', '.').toDoubleOrNull() ?: return@Button
                if (q > 0 && p > 0) onSubmit(q, p, deliveryType, preferredDay, note)
            }) {
                Text(l10n.get(R.string.confirm_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(l10n.get(R.string.cancel_btn)) }
        }
    )
}
