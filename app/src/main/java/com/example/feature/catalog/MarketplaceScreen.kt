package com.example.feature.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.data.*
import com.example.core.domain.DynamicFormSchemaCodec
import com.example.core.l10n.LocalL10n
import com.example.core.ui.*
import com.example.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarketplaceScreen(
    state: AppUiState,
    onUpdateFilter: ((MarketplaceFilter) -> MarketplaceFilter) -> Unit,
    onClearFilters: () -> Unit,
    onSaveFilter: (String) -> Unit,
    onApplySavedFilter: (SavedFilterEntity) -> Unit,
    onDeleteSavedFilter: (String) -> Unit,
    onOpenOffer: (OfferEntity) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onOpenNotifications: () -> Unit,
    onLoadMore: () -> Unit = {}
) {
    val l10n = LocalL10n.current
    val user = state.currentUser
    var showFilterSheet by remember { mutableStateOf(false) }
    var showSaveFilterDialog by remember { mutableStateOf(false) }

    val filteredOffers = remember(state.offers, state.filter, state.products, state.categories, state.profiles, state.farmerDetails, state.favoriteOfferIds, user?.city) {
        val q = state.filter.searchQuery.trim().lowercase()
        val list = state.offers.filter { offer ->
            if (offer.status !in setOf(OfferStatus.ACTIVE, OfferStatus.RESERVED)) return@filter false
            val prod = state.products.firstOrNull { it.id == offer.productId }
            val farmer = state.profiles.firstOrNull { it.id == offer.farmerId }
            val farmDetails = state.farmerDetails.firstOrNull { it.userId == offer.farmerId }
            val category = state.categories.firstOrNull { it.id == offer.categoryId }
            val matchesQuery = q.isEmpty() ||
                offer.city.lowercase().contains(q) ||
                prod?.nameDarija?.lowercase()?.contains(q) == true ||
                prod?.nameAr?.lowercase()?.contains(q) == true ||
                prod?.nameFr?.lowercase()?.contains(q) == true ||
                farmer?.fullName?.lowercase()?.contains(q) == true ||
                farmDetails?.farmOrCoopName?.lowercase()?.contains(q) == true ||
                category?.nameDarija?.lowercase()?.contains(q) == true ||
                category?.nameAr?.lowercase()?.contains(q) == true ||
                category?.nameFr?.lowercase()?.contains(q) == true
            val matchesCat = state.filter.categoryId == null || offer.categoryId == state.filter.categoryId
            val matchesProd = state.filter.productId == null || offer.productId == state.filter.productId
            val matchesCity = state.filter.city.isNullOrBlank() || offer.city == state.filter.city
            val matchesPrice = state.filter.maxPrice == null || offer.askingPrice <= state.filter.maxPrice!!
            val matchesQty = state.filter.minQty == null || offer.quantity >= state.filter.minQty!!
            val matchesFavorite = !state.filter.favoritesOnly || offer.id in state.favoriteOfferIds
            matchesQuery && matchesCat && matchesProd && matchesCity && matchesPrice && matchesQty && matchesFavorite
        }

        when (state.filter.sortBy) {
            OfferSortOption.NEWEST -> list.sortedByDescending { it.createdAt }
            OfferSortOption.PRICE_LOW -> list.sortedBy { it.askingPrice }
            OfferSortOption.RATING -> list.sortedByDescending { off ->
                state.profiles.firstOrNull { it.id == off.farmerId }?.ratingAvg ?: 0.0
            }
            OfferSortOption.DISTANCE -> list.sortedBy { off ->
                if (off.city == user?.city) 0 else 1
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(WarmParchment),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            // Compact Souss-Massa Header Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(116.dp)
                    .clip(MaterialTheme.shapes.large)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_souss_hero),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(DeepOlive.copy(alpha = 0.90f), DeepOlive.copy(alpha = 0.55f))
                            )
                        )
                )
                Row(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = l10n.get(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            color = CrispSurface
                        )
                        Text(
                            text = user?.city ?: "سوس ماسة",
                            style = MaterialTheme.typography.bodyLarge,
                            color = SoftSageContainer
                        )
                    }
                    BadgedBox(
                        badge = {
                            if (state.unreadNotificationCount > 0) {
                                Badge(containerColor = HarvestTerracotta) {
                                    Text(state.unreadNotificationCount.toString())
                                }
                            }
                        }
                    ) {
                        FilledIconButton(
                            onClick = onOpenNotifications,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = CrispSurface.copy(alpha = 0.92f)),
                            modifier = Modifier.testTag("notifications_button")
                        ) {
                            Icon(Icons.Rounded.Notifications, contentDescription = l10n.get(R.string.notifications_title), tint = ForestGreen)
                        }
                    }
                }
            }
        }

        if (user != null && user.verificationStatus != VerificationStatus.VERIFIED) {
            item { VerificationBanner(user) }
        }

        // Search & Filter buttons
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.filter.searchQuery,
                    onValueChange = { txt -> onUpdateFilter { it.copy(searchQuery = txt) } },
                    placeholder = { Text(l10n.get(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = CrispSurface, unfocusedContainerColor = CrispSurface),
                    modifier = Modifier.weight(1f).testTag("market_search_input")
                )
                FilledTonalIconButton(
                    onClick = { showFilterSheet = true },
                    modifier = Modifier.size(54.dp).testTag("open_filters_button")
                ) {
                    Icon(Icons.Rounded.Tune, contentDescription = l10n.get(R.string.filters_btn))
                }
            }
        }

        // Category strip
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.filter.categoryId == null,
                    onClick = { onUpdateFilter { it.copy(categoryId = null, productId = null) } },
                    label = { Text(l10n.get(R.string.all_categories)) }
                )
                state.categories.forEach { cat ->
                    FilterChip(
                        selected = state.filter.categoryId == cat.id,
                        onClick = {
                            onUpdateFilter { f ->
                                val next = if (f.categoryId == cat.id) null else cat.id
                                f.copy(categoryId = next, productId = null)
                            }
                        },
                        label = { Text(l10n.localizedName(cat.nameDarija, cat.nameAr, cat.nameFr)) }
                    )
                }
                if (user != null) {
                    FilterChip(
                        selected = state.filter.favoritesOnly,
                        onClick = { onUpdateFilter { it.copy(favoritesOnly = !it.favoritesOnly) } },
                        label = { Text(l10n.get(R.string.favorites_only)) },
                        leadingIcon = { Icon(Icons.Rounded.Favorite, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("favorites_only_filter")
                    )
                }
            }
        }

        // Saved filters (for Buyers)
        if (user?.role == UserRole.BUYER && (state.savedFilters.isNotEmpty() || state.filter.categoryId != null || state.filter.city != null || state.filter.maxPrice != null)) {
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.filter.categoryId != null || state.filter.city != null || state.filter.maxPrice != null) {
                        AssistChip(
                            onClick = { showSaveFilterDialog = true },
                            label = { Text(l10n.get(R.string.save_filter_btn)) },
                            leadingIcon = { Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                    state.savedFilters.forEach { sf ->
                        InputChip(
                            selected = false,
                            onClick = { onApplySavedFilter(sf) },
                            label = { Text(sf.label) },
                            trailingIcon = {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp).clickable { onDeleteSavedFilter(sf.id) }
                                )
                            }
                        )
                    }
                }
            }
        }

        if (!state.isLoaded) {
            items(3) { SkeletonCardPlaceholder() }
        } else if (filteredOffers.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Rounded.SearchOff,
                    title = l10n.get(R.string.empty_offers_title),
                    subtitle = l10n.get(R.string.empty_offers_desc)
                )
            }
        } else {
            items(filteredOffers, key = { it.id }) { offer ->
                val product = state.products.firstOrNull { it.id == offer.productId }
                val farmer = state.profiles.firstOrNull { it.id == offer.farmerId }
                val productName = product?.let { l10n.localizedName(it.nameDarija, it.nameAr, it.nameFr) } ?: offer.productId
                val attrs = DynamicFormSchemaCodec.parseValues(offer.attributesJson)
                val firstPhoto = offer.photoUrisCsv.split(",").firstOrNull()?.takeIf { it.isNotBlank() } ?: "drawable:hero"

                Card(
                    colors = CardDefaults.cardColors(containerColor = CrispSurface),
                    border = BorderStroke(1.dp, SubtleBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("offer_card_${offer.id}")
                        .clickable { onOpenOffer(offer) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OfferPhotoView(
                            photoToken = firstPhoto,
                            contentDescription = productName,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(MaterialTheme.shapes.medium)
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(productName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = { onToggleFavorite(offer.id) },
                                    modifier = Modifier.size(36.dp).testTag("favorite_offer_${offer.id}")
                                ) {
                                    Icon(
                                        imageVector = if (offer.id in state.favoriteOfferIds) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                        contentDescription = l10n.get(if (offer.id in state.favoriteOfferIds) R.string.remove_favorite else R.string.add_favorite),
                                        tint = if (offer.id in state.favoriteOfferIds) HarvestTerracotta else MutedMossText
                                    )
                                }
                                if (offer.voiceNotePath.isNotBlank()) {
                                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = ForestGreen, modifier = Modifier.size(20.dp))
                                }
                            }
                            Text(
                                text = "${formatMad(offer.askingPrice)} ${l10n.get(R.string.currency_mad)} / ${l10n.unitLabel(offer.unit)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = ForestGreen,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${formatMad(offer.quantity)} ${l10n.unitLabel(offer.unit)} • ${offer.city}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MutedMossText
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                attrs.values.take(2).forEach { v ->
                                    StatusPill(v, SoftFieldSurface, InkCharcoal)
                                }
                                if (offer.syncState == SyncState.PENDING_SYNC) {
                                    StatusPill(l10n.get(R.string.pending_sync_badge), SaffronContainer, OnSaffronContainer)
                                } else if (offer.mediaStatus == MediaStatus.UPLOADING) {
                                    StatusPill(l10n.get(R.string.media_uploading_badge), SaffronContainer, OnSaffronContainer)
                                } else if (offer.mediaStatus == MediaStatus.FAILED) {
                                    StatusPill(l10n.get(R.string.media_failed_badge), ErrorContainerLight, ErrorCrimson)
                                }
                            }
                            if (farmer != null) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Rounded.Verified, contentDescription = null, tint = FreshLeaf, modifier = Modifier.size(16.dp))
                                    Text(farmer.fullName, style = MaterialTheme.typography.labelMedium, color = MutedMossText)
                                    if (farmer.ratingCount > 0) {
                                        Icon(Icons.Rounded.Star, contentDescription = null, tint = SaffronGold, modifier = Modifier.size(16.dp))
                                        Text(String.format(java.util.Locale.US, "%.1f", farmer.ratingAvg), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (state.hasMoreOffers) {
                item {
                    OutlinedButton(
                        onClick = onLoadMore,
                        enabled = !state.isLoadingMoreOffers,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("load_more_offers_button")
                    ) {
                        if (state.isLoadingMoreOffers) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(l10n.get(R.string.load_more_btn))
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        FilterModalDialog(
            state = state,
            onDismiss = { showFilterSheet = false },
            onApply = { city, maxPrice, minQty, sortBy ->
                onUpdateFilter { it.copy(city = city, maxPrice = maxPrice, minQty = minQty, sortBy = sortBy) }
                showFilterSheet = false
            },
            onClear = {
                onClearFilters()
                showFilterSheet = false
            }
        )
    }

    if (showSaveFilterDialog) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveFilterDialog = false },
            title = { Text(l10n.get(R.string.save_filter_btn)) },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(l10n.get(R.string.saved_filters_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onSaveFilter(label.ifBlank { state.filter.city ?: "Alert" })
                    showSaveFilterDialog = false
                }) { Text(l10n.get(R.string.save_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveFilterDialog = false }) { Text(l10n.get(R.string.cancel_btn)) }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterModalDialog(
    state: AppUiState,
    onDismiss: () -> Unit,
    onApply: (String?, Double?, Double?, OfferSortOption) -> Unit,
    onClear: () -> Unit
) {
    val l10n = LocalL10n.current
    val allCities = remember(state.regions) {
        state.regions.flatMap { it.citiesCsv.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    var selectedCity by remember { mutableStateOf(state.filter.city) }
    var maxPriceText by remember { mutableStateOf(state.filter.maxPrice?.let { formatMad(it) }.orEmpty()) }
    var minQtyText by remember { mutableStateOf(state.filter.minQty?.let { formatMad(it) }.orEmpty()) }
    var sortBy by remember { mutableStateOf(state.filter.sortBy) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(l10n.get(R.string.filters_btn)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(l10n.get(R.string.sort_label), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        OfferSortOption.NEWEST to R.string.sort_newest,
                        OfferSortOption.PRICE_LOW to R.string.sort_price_low,
                        OfferSortOption.RATING to R.string.sort_rating,
                        OfferSortOption.DISTANCE to R.string.sort_distance
                    ).forEach { (opt, resId) ->
                        FilterChip(selected = sortBy == opt, onClick = { sortBy = opt }, label = { Text(l10n.get(resId)) })
                    }
                }

                Text(l10n.get(R.string.city_label), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    allCities.forEach { c ->
                        FilterChip(
                            selected = selectedCity == c,
                            onClick = { selectedCity = if (selectedCity == c) null else c },
                            label = { Text(c) }
                        )
                    }
                }

                OutlinedTextField(
                    value = maxPriceText,
                    onValueChange = { maxPriceText = it },
                    label = { Text(l10n.get(R.string.max_price_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = minQtyText,
                    onValueChange = { minQtyText = it },
                    label = { Text(l10n.get(R.string.min_qty_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onApply(
                    selectedCity,
                    maxPriceText.replace(',', '.').toDoubleOrNull(),
                    minQtyText.replace(',', '.').toDoubleOrNull(),
                    sortBy
                )
            }) { Text(l10n.get(R.string.apply_filters_btn)) }
        },
        dismissButton = {
            TextButton(onClick = onClear) { Text(l10n.get(R.string.clear_filters_btn)) }
        }
    )
}
