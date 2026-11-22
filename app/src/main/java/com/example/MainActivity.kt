package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.data.OfferEntity
import com.example.core.data.UserRole
import com.example.core.l10n.L10nStrings
import com.example.core.l10n.LocalL10n
import com.example.core.ui.AppUiState
import com.example.core.ui.FellahViewModel
import com.example.core.ui.LocalMediaCache
import com.example.core.ui.OfflineStatusBanner
import com.example.feature.admin.AdminDashboardScreen
import com.example.feature.auth.ForceChangePinScreen
import com.example.feature.auth.LanguageSelectionScreen
import com.example.feature.auth.PhonePinAuthScreen
import com.example.feature.auth.RoleAndProfileSetupScreen
import com.example.feature.catalog.MarketplaceScreen
import com.example.feature.deals.DealsScreen
import com.example.feature.offers.CreateOfferScreen
import com.example.feature.offers.MyOffersScreen
import com.example.feature.offers.OfferDetailScreen
import com.example.feature.prices.MarketPricesScreen
import com.example.feature.profile.DiagnosticsScreen
import com.example.feature.profile.NotificationsScreen
import com.example.feature.profile.ProfileScreen
import com.example.feature.requests.WantedRequestsScreen
import com.example.ui.theme.FellahMarketTheme

private enum class MainTab {
    MARKET,
    MY_OFFERS,
    WANTED,
    DEALS,
    PRICES,
    ADMIN,
    PROFILE
}

private data class NavDestination(
    val tab: MainTab,
    val labelRes: Int,
    val icon: ImageVector
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FellahMarketRoot()
        }
    }
}

@Composable
fun FellahMarketRoot(vm: FellahViewModel = viewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val voiceState by vm.voiceState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val l10n = remember(state.language) {
        L10nStrings.create(context, state.language)
    }

    var showLanguagePickerOverride by remember { mutableStateOf(false) }

    FellahMarketTheme {
        CompositionLocalProvider(
            LocalL10n provides l10n,
            LocalLayoutDirection provides state.language.layoutDirection,
            LocalMediaCache provides vm.mediaCache
        ) {
            when {
                !state.hasSelectedLanguage || showLanguagePickerOverride -> {
                    LanguageSelectionScreen(
                        currentLanguage = state.language,
                        onSelectLanguage = { vm.selectLanguage(it) },
                        onContinue = {
                            vm.selectLanguage(state.language)
                            showLanguagePickerOverride = false
                        }
                    )
                }

                state.auth.pendingPhoneForRegistration != null -> {
                    RoleAndProfileSetupScreen(
                        regions = state.regions,
                        categories = state.categories,
                        onSubmit = { name, role, regId, city, farm, biz, bizType, cats, docPath ->
                            vm.completeProfileRegistration(name, role, regId, city, farm, biz, bizType, cats, docPath)
                        },
                        onCancel = { vm.cancelRegistration() }
                    )
                }

                state.currentUser == null -> {
                    PhonePinAuthScreen(
                        authState = state.auth,
                        isDemoMode = state.isDemoMode,
                        onModeChange = { vm.setAuthMode(it) },
                        onPhoneChange = { vm.updatePhoneInput(it) },
                        onPinChange = { vm.updatePinInput(it) },
                        onConfirmPinChange = { vm.updateConfirmPinInput(it) },
                        onSubmitAuth = { vm.submitAuth() },
                        onQuickDemoLogin = { vm.loginDemoUser(it) },
                        onChangeLanguageClick = { showLanguagePickerOverride = true }
                    )
                }

                state.currentUser?.mustChangePin == true -> {
                    ForceChangePinScreen(
                        onChangePin = { cur, next, conf, cb -> vm.changePin(cur, next, conf, cb) },
                        onLogout = { vm.logout() }
                    )
                }

                else -> {
                    AuthenticatedAppShell(
                        state = state,
                        vm = vm,
                        voiceState = voiceState
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthenticatedAppShell(
    state: AppUiState,
    vm: FellahViewModel,
    voiceState: com.example.core.media.VoiceUiState
) {
    val l10n = LocalL10n.current
    val context = LocalContext.current
    val user = state.currentUser ?: return

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(user.id, state.isDemoMode) {
        if (!state.isDemoMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val destinations = remember(user.role) {
        when (user.role) {
            UserRole.FARMER -> listOf(
                NavDestination(MainTab.MY_OFFERS, R.string.nav_my_offers, Icons.Rounded.Inventory2),
                NavDestination(MainTab.WANTED, R.string.nav_wanted, Icons.Rounded.Campaign),
                NavDestination(MainTab.DEALS, R.string.nav_deals, Icons.Rounded.Handshake),
                NavDestination(MainTab.PRICES, R.string.nav_prices, Icons.AutoMirrored.Rounded.TrendingUp),
                NavDestination(MainTab.PROFILE, R.string.nav_profile, Icons.Rounded.Person)
            )
            UserRole.ADMIN -> listOf(
                NavDestination(MainTab.ADMIN, R.string.nav_admin, Icons.Rounded.AdminPanelSettings),
                NavDestination(MainTab.MARKET, R.string.nav_market, Icons.Rounded.Storefront),
                NavDestination(MainTab.DEALS, R.string.nav_deals, Icons.Rounded.Handshake),
                NavDestination(MainTab.PRICES, R.string.nav_prices, Icons.AutoMirrored.Rounded.TrendingUp),
                NavDestination(MainTab.PROFILE, R.string.nav_profile, Icons.Rounded.Person)
            )
            else -> listOf(
                NavDestination(MainTab.MARKET, R.string.nav_market, Icons.Rounded.Storefront),
                NavDestination(MainTab.WANTED, R.string.nav_wanted, Icons.Rounded.Campaign),
                NavDestination(MainTab.DEALS, R.string.nav_deals, Icons.Rounded.Handshake),
                NavDestination(MainTab.PRICES, R.string.nav_prices, Icons.AutoMirrored.Rounded.TrendingUp),
                NavDestination(MainTab.PROFILE, R.string.nav_profile, Icons.Rounded.Person)
            )
        }
    }

    var activeTab by remember(user.id, user.role) { mutableStateOf(destinations.first().tab) }
    var showCreateOffer by remember { mutableStateOf(false) }
    var editingOffer by remember { mutableStateOf<OfferEntity?>(null) }
    var selectedOfferDetail by remember { mutableStateOf<OfferEntity?>(null) }
    var showNotifications by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    when {
        showCreateOffer -> {
            CreateOfferScreen(
                state = state,
                editingOffer = editingOffer,
                voiceState = voiceState,
                voiceManager = vm.voiceManager,
                onCompressPhoto = { uri -> vm.compressPickedImage(uri) },
                onGetMarketSummary = { pid, rid, unit -> vm.getMarketSummary(pid, rid, unit) },
                onSaveOffer = { id, prod, city, regId, qty, unit, price, day, attrs, photos, vPath, vSec ->
                    vm.saveOffer(id, prod, city, regId, qty, unit, price, day, attrs, photos, vPath, vSec)
                },
                onSubmitProposal = { n, c, u, notes -> vm.submitProductProposal(n, c, u, notes) },
                onBack = {
                    showCreateOffer = false
                    editingOffer = null
                }
            )
        }

        selectedOfferDetail != null -> {
            val freshOffer = state.offers.firstOrNull { it.id == selectedOfferDetail!!.id } ?: selectedOfferDetail!!
            val summary = remember(freshOffer.productId, freshOffer.regionId, freshOffer.unit, state.deals, state.offers, state.referencePrices) {
                vm.getMarketSummary(freshOffer.productId, freshOffer.regionId, freshOffer.unit)
            }
            OfferDetailScreen(
                offer = freshOffer,
                state = state,
                voiceState = voiceState,
                voiceManager = vm.voiceManager,
                marketSummary = summary,
                onSendDealRequest = { qty, price, delivery, day, note ->
                    vm.sendDealFromOffer(freshOffer, qty, price, delivery, day, note)
                },
                onReportOffer = { reason -> vm.reportTarget("offer", freshOffer.id, reason) },
                onPlayVoice = { vm.toggleVoicePlayback(it) },
                onBack = { selectedOfferDetail = null }
            )
        }

        showNotifications -> {
            NotificationsScreen(
                state = state,
                onMarkAllRead = { vm.markNotificationsRead() },
                onBack = { showNotifications = false }
            )
        }

        showDiagnostics -> {
            DiagnosticsScreen(
                state = state,
                onBack = { showDiagnostics = false }
            )
        }

        else -> {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useRail = maxWidth >= 600.dp

                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        if (!useRail) {
                            NavigationBar {
                                destinations.forEach { dest ->
                                    NavigationBarItem(
                                        selected = activeTab == dest.tab,
                                        onClick = { activeTab = dest.tab },
                                        icon = { Icon(dest.icon, contentDescription = l10n.get(dest.labelRes)) },
                                        label = { Text(l10n.get(dest.labelRes), maxLines = 1) },
                                        modifier = Modifier.testTag("nav_${dest.tab.name.lowercase()}")
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        if (useRail) {
                            NavigationRail {
                                destinations.forEach { dest ->
                                    NavigationRailItem(
                                        selected = activeTab == dest.tab,
                                        onClick = { activeTab = dest.tab },
                                        icon = { Icon(dest.icon, contentDescription = l10n.get(dest.labelRes)) },
                                        label = { Text(l10n.get(dest.labelRes), maxLines = 1) }
                                    )
                                }
                            }
                        }

                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            OfflineStatusBanner(
                                isOnline = state.isEffectivelyOnline,
                                pendingSyncCount = state.pendingSyncCount,
                                onSyncNow = { vm.syncNow() },
                                failedSyncCount = state.failedSyncItems.size,
                                onRetryFailedSync = { vm.retryFailedSync() }
                            )

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                when (activeTab) {
                                    MainTab.MARKET -> MarketplaceScreen(
                                        state = state,
                                        onUpdateFilter = { vm.updateFilter(it) },
                                        onClearFilters = { vm.clearFilters() },
                                        onSaveFilter = { vm.saveCurrentFilter(it) },
                                        onApplySavedFilter = { vm.applySavedFilter(it) },
                                        onDeleteSavedFilter = { vm.deleteSavedFilter(it) },
                                        onOpenOffer = { selectedOfferDetail = it },
                                        onToggleFavorite = { vm.toggleFavoriteOffer(it) },
                                        onOpenNotifications = { showNotifications = true },
                                        onLoadMore = { vm.loadMoreOffers() }
                                    )
                                    MainTab.MY_OFFERS -> MyOffersScreen(
                                        state = state,
                                        onCreateOffer = {
                                            editingOffer = null
                                            showCreateOffer = true
                                        },
                                        onEditOffer = { off ->
                                            editingOffer = off
                                            showCreateOffer = true
                                        },
                                        onSetOfferStatus = { id, st -> vm.setOfferStatus(id, st) },
                                        onDuplicateOffer = { vm.duplicateOffer(it) },
                                        onOpenOfferDetail = { selectedOfferDetail = it },
                                        onRetryMediaUpload = { vm.retryOfferMediaUpload(it) }
                                    )
                                    MainTab.WANTED -> WantedRequestsScreen(
                                        state = state,
                                        onPostWanted = { p, city, reg, q, u, mp, day, n ->
                                            vm.postBuyRequest(p, city, reg, q, u, mp, day, n)
                                        },
                                        onRespondToWanted = { req, price, del, note ->
                                            vm.respondToWantedPost(req, price, del, note)
                                        }
                                    )
                                    MainTab.DEALS -> DealsScreen(
                                        state = state,
                                        onAcceptDeal = { vm.acceptDeal(it) },
                                        onRejectDeal = { vm.cancelDeal(it) },
                                        onCounterDeal = { d, p -> vm.counterDeal(d, p) },
                                        onConfirmCompletion = { vm.confirmDealCompletion(it) },
                                        onRatePartner = { d, s, c -> vm.rateDealPartner(d, s, c) },
                                        onDismissError = { vm.clearDealActionError() }
                                    )
                                    MainTab.PRICES -> MarketPricesScreen(state = state)
                                    MainTab.ADMIN -> AdminDashboardScreen(
                                        state = state,
                                        onApproveUser = { vm.approveUser(it) },
                                        onRejectUser = { id, r -> vm.rejectUser(id, r) },
                                        onResetUserPin = { uid, pin, cb -> vm.adminResetUserPin(uid, pin, cb) },
                                        onAddProduct = { c, nd, na, nf, u, sc -> vm.adminAddProduct(c, nd, na, nf, u, sc) },
                                        onSetReferencePrice = { p, r, u, pr -> vm.adminSetReferencePrice(p, r, u, pr) },
                                        onApproveProposal = { vm.adminApproveProposal(it) },
                                        onRejectProposal = { vm.adminRejectProposal(it) },
                                        onResolveReport = { vm.adminResolveReport(it) },
                                        onDismissError = { vm.clearSyncError() }
                                    )
                                    MainTab.PROFILE -> ProfileScreen(
                                        state = state,
                                        onSelectLanguage = { vm.selectLanguage(it) },
                                        onToggleSimulateOffline = { vm.toggleSimulateOffline(it) },
                                        onSyncNow = { vm.syncNow() },
                                        onRetryFailedSync = { vm.retryFailedSync() },
                                        onUploadVerificationDoc = { vm.uploadVerificationDoc(it) },
                                        onChangePin = { cur, next, conf, cb -> vm.changePin(cur, next, conf, cb) },
                                        onOpenDiagnostics = { showDiagnostics = true },
                                        onSwitchDemoUser = { vm.loginDemoUser(it) },
                                        onLogout = { vm.logout() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
