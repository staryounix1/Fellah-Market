package com.example.core.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.auth.*
import com.example.core.data.*
import com.example.core.domain.PhoneValidator
import com.example.core.domain.PinValidationError
import com.example.core.domain.PinValidator
import com.example.core.domain.PriceAnalytics
import com.example.core.domain.ProductMarketSummary
import com.example.core.l10n.AppLanguage
import com.example.core.media.ImageCompressor
import com.example.core.media.MediaSyncManager
import com.example.core.media.SignedUrlMediaCache
import com.example.core.media.VoiceNoteManager
import com.example.core.media.VoiceUiState
import com.example.core.network.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

enum class OfferSortOption { NEWEST, PRICE_LOW, RATING, DISTANCE }

data class MarketplaceFilter(
    val searchQuery: String = "",
    val categoryId: String? = null,
    val productId: String? = null,
    val city: String? = null,
    val maxPrice: Double? = null,
    val minQty: Double? = null,
    val favoritesOnly: Boolean = false,
    val sortBy: OfferSortOption = OfferSortOption.NEWEST
)

data class AuthUiState(
    val phoneInput: String = "",
    val isSignUpMode: Boolean = false,
    val pinInput: String = "",
    val confirmPinInput: String = "",
    val lockoutRemainingSeconds: Int = 0,
    val pinError: PinValidationError? = null,
    val pendingPhoneForRegistration: String? = null,
    val pendingUserIdForRegistration: String? = null,
    val errorKey: String? = null,
    val isLoading: Boolean = false
)

data class AppUiState(
    val isLoaded: Boolean = false,
    val isDemoMode: Boolean = SupabaseConfig.demoMode,
    val isRemoteConfigured: Boolean = SupabaseConfig.isConfigured,
    val hasActiveSession: Boolean = false,
    val sessionExpiresAtEpochSec: Long? = null,
    val language: AppLanguage = AppLanguage.DARIJA,
    val hasSelectedLanguage: Boolean = false,
    val currentUser: ProfileEntity? = null,
    val auth: AuthUiState = AuthUiState(),
    val isNetworkConnected: Boolean = true,
    val simulateOffline: Boolean = false,
    val pendingSyncCount: Int = 0,
    val failedSyncItems: List<SyncQueueEntity> = emptyList(),
    val hasMoreOffers: Boolean = false,
    val isLoadingMoreOffers: Boolean = false,
    val currentOffersPage: Int = 0,
    val syncErrorMessage: String? = null,
    val dealActionError: String? = null,
    val remoteMarketSummaries: Map<String, ProductMarketSummary> = emptyMap(),
    val regions: List<RegionEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val products: List<ProductEntity> = emptyList(),
    val profiles: List<ProfileEntity> = emptyList(),
    val farmerDetails: List<FarmerDetailsEntity> = emptyList(),
    val buyerDetails: List<BuyerDetailsEntity> = emptyList(),
    val offers: List<OfferEntity> = emptyList(),
    val buyRequests: List<BuyRequestEntity> = emptyList(),
    val deals: List<DealEntity> = emptyList(),
    val dealHistory: List<DealStatusHistoryEntity> = emptyList(),
    val ratings: List<RatingEntity> = emptyList(),
    val notifications: List<NotificationEntity> = emptyList(),
    val savedFilters: List<SavedFilterEntity> = emptyList(),
    val favoriteOfferIds: Set<String> = emptySet(),
    val referencePrices: List<ReferencePriceEntity> = emptyList(),
    val productProposals: List<ProductProposalEntity> = emptyList(),
    val reports: List<ReportEntity> = emptyList(),
    val filter: MarketplaceFilter = MarketplaceFilter()
) {
    val isEffectivelyOnline: Boolean get() = isNetworkConnected && !simulateOffline
    val unreadNotificationCount: Int get() = notifications.count { !it.isRead }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FellahViewModel(application: Application) : AndroidViewModel(application) {
    private val db = FellahDatabase.getInstance(application)
    private val prefs = PreferencesStore(application)
    private val sessionManager = SessionManager(AndroidKeystoreEncryptedStore(application))
    private val authClient = SupabaseAuthClient(
        baseUrlProvider = { SupabaseConfig.url },
        anonKeyProvider = { SupabaseConfig.anonKey },
        demoModeProvider = { SupabaseConfig.demoMode },
        sessionManager = sessionManager,
        lockoutStore = prefs
    )

    private val _authState = MutableStateFlow(AuthUiState())
    private val _filterState = MutableStateFlow(MarketplaceFilter())
    private val _isLoaded = MutableStateFlow(false)
    private val _paginationState = MutableStateFlow(PaginationUiState())
    private val _syncErrorMessage = MutableStateFlow<String?>(null)
    private val _dealActionError = MutableStateFlow<String?>(null)
    private val _remoteMarketSummaries = MutableStateFlow<Map<String, ProductMarketSummary>>(emptyMap())

    private val supabaseRepo = SupabaseRepository(
        dao = db.dao(),
        authClient = authClient,
        sessionManager = sessionManager,
        baseUrlProvider = { SupabaseConfig.url },
        anonKeyProvider = { SupabaseConfig.anonKey },
        onSessionExpired = { handleSessionExpired() }
    )
    private val repository = FellahRepository(
        dao = db.dao(),
        authClient = authClient,
        sessionManager = sessionManager,
        supabaseRepo = supabaseRepo,
        notificationPort = LocalAndFcmNotificationPort(),
        prefsStore = prefs,
        isDemoModeProvider = { SupabaseConfig.demoMode }
    )

    private val connectivity = ConnectivityObserver(application)
    private val mediaSyncManager = MediaSyncManager(application)
    val mediaCache = SignedUrlMediaCache(application, authClient)
    private val notificationSource: RealtimeNotificationSource = PollingNotificationSource(
        context = application,
        supabaseRepo = supabaseRepo,
        sessionManager = sessionManager
    )
    val voiceManager = VoiceNoteManager(application, viewModelScope)
    val voiceState: StateFlow<VoiceUiState> = voiceManager.state

    private var lockoutTickerJob: Job? = null

    val uiState: StateFlow<AppUiState>

    init {
        viewModelScope.launch {
            val savedSession = sessionManager.getSession()
            val storedUid = prefs.currentUserIdFlow.first()
            val activeUid = if (savedSession != null && storedUid == null) {
                prefs.setCurrentUserId(savedSession.userId)
                savedSession.userId
            } else {
                storedUid
            }
            val lockoutSec = authClient.lockoutManager.remainingLockoutSeconds()
            if (lockoutSec > 0) {
                _authState.update { it.copy(lockoutRemainingSeconds = lockoutSec, errorKey = "account_locked") }
                startLockoutTicker(lockoutSec)
            }
            val cachedProfile = activeUid?.let { db.dao().getProfileById(it) }
            repository.ensureSeededOrRefreshed(activeUid, cachedProfile?.role == UserRole.ADMIN)
            if (supabaseRepo.isRemoteConfigured() && savedSession != null) {
                fetchOffersPageInternal(page = 0)
            }
            _isLoaded.value = true
        }

        // Start/stop foreground notification polling whenever active user changes
        viewModelScope.launch {
            prefs.currentUserIdFlow.collectLatest { uid ->
                if (uid != null && supabaseRepo.isRemoteConfigured()) {
                    val profile = db.dao().getProfileById(uid)
                    notificationSource.start(
                        scope = viewModelScope,
                        userId = uid,
                        isAdmin = profile?.role == UserRole.ADMIN,
                        isOnline = { uiState.value.isEffectivelyOnline },
                        languageProvider = { uiState.value.language }
                    )
                } else {
                    notificationSource.stop()
                }
            }
        }

        // Auto-sync when connectivity returns
        viewModelScope.launch {
            combine(connectivity.isConnected, prefs.simulateOfflineFlow) { net, sim -> net && !sim }
                .distinctUntilChanged()
                .collect { online ->
                    if (online) {
                        val summary = repository.syncPendingItemsNow()
                        if (summary.failedCount > 0) {
                            _syncErrorMessage.value = "${summary.failedCount} failed"
                        }
                    }
                }
        }

        val userIdFlow = prefs.currentUserIdFlow
        val currentUserFlow = userIdFlow.flatMapLatest { uid ->
            if (uid == null) flowOf(null) else repository.observeProfile(uid)
        }
        val userNotificationsFlow = userIdFlow.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList()) else repository.observeNotifications(uid)
        }
        val userSavedFiltersFlow = userIdFlow.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList()) else repository.observeSavedFilters(uid)
        }
        val userFavoriteOffersFlow = userIdFlow.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList()) else repository.observeFavoriteOffers(uid)
        }

        val sessionFlow = combine(
            _isLoaded,
            prefs.languageFlow,
            prefs.hasSelectedLanguageFlow,
            currentUserFlow,
            _authState
        ) { loaded, lang, hasLang, user, auth ->
            SessionBundle(loaded, lang, hasLang, user, auth)
        }

        val netFlow = combine(
            connectivity.isConnected,
            prefs.simulateOfflineFlow,
            repository.pendingSyncQueue,
            repository.failedSyncQueue,
            _syncErrorMessage
        ) { net, sim, queue, failed, syncErr ->
            NetBundle(net, sim, queue.size, failed, syncErr)
        }

        val catalogFlow = combine(
            repository.regions,
            repository.categories,
            repository.products,
            repository.allProfiles,
            repository.allFarmerDetails
        ) { regs, cats, prods, profs, fds ->
            CatalogBundle(regs, cats, prods, profs, fds)
        }

        val marketFlow = combine(
            repository.allBuyerDetails,
            repository.offers,
            repository.buyRequests,
            repository.deals,
            repository.dealHistory
        ) { bds, offs, reqs, dls, hist ->
            MarketBundle(bds, offs, reqs, dls, hist)
        }

        val catalogExtrasFlow = combine(
            repository.referencePrices,
            repository.productProposals,
            userFavoriteOffersFlow
        ) { refs, props, favorites ->
            Triple(refs, props, favorites.map { it.offerId }.toSet())
        }
        val extraFlow = combine(
            repository.ratings,
            userNotificationsFlow,
            userSavedFiltersFlow,
            catalogExtrasFlow
        ) { rats, notifs, flts, extras ->
            ExtraBundle(rats, notifs, flts, extras.first, extras.second, extras.third)
        }

        val uiMetaFlow = combine(
            _paginationState,
            _dealActionError,
            _remoteMarketSummaries,
            repository.reports,
            _filterState
        ) { pag, dealErr, summaries, reps, flt ->
            MetaBundle(pag, dealErr, summaries, reps, flt)
        }

        uiState = combine(
            sessionFlow,
            netFlow,
            catalogFlow,
            marketFlow,
            extraFlow,
            uiMetaFlow
        ) { arr ->
            val session = arr[0] as SessionBundle
            val net = arr[1] as NetBundle
            val cat = arr[2] as CatalogBundle
            val mkt = arr[3] as MarketBundle
            val ext = arr[4] as ExtraBundle
            val meta = arr[5] as MetaBundle

            val activeSession = sessionManager.getSession()
            AppUiState(
                isLoaded = session.loaded,
                isDemoMode = SupabaseConfig.demoMode,
                isRemoteConfigured = SupabaseConfig.isConfigured,
                hasActiveSession = activeSession != null,
                sessionExpiresAtEpochSec = activeSession?.expiresAtEpochSec,
                language = session.lang,
                hasSelectedLanguage = session.hasLang,
                currentUser = session.user,
                auth = session.auth,
                isNetworkConnected = net.isConnected,
                simulateOffline = net.simulateOffline,
                pendingSyncCount = net.pendingCount,
                failedSyncItems = net.failedItems,
                hasMoreOffers = meta.pagination.hasMore,
                isLoadingMoreOffers = meta.pagination.isLoadingMore,
                currentOffersPage = meta.pagination.page,
                syncErrorMessage = net.syncError,
                dealActionError = meta.dealActionError,
                remoteMarketSummaries = meta.summaries,
                regions = cat.regions,
                categories = cat.categories,
                products = cat.products,
                profiles = cat.profiles,
                farmerDetails = cat.farmerDetails,
                buyerDetails = mkt.buyerDetails,
                offers = mkt.offers,
                buyRequests = mkt.buyRequests,
                deals = mkt.deals,
                dealHistory = mkt.dealHistory,
                ratings = ext.ratings,
                notifications = ext.notifications,
                savedFilters = ext.savedFilters,
                favoriteOfferIds = ext.favoriteOfferIds,
                referencePrices = ext.referencePrices,
                productProposals = ext.proposals,
                reports = meta.reports,
                filter = meta.filter
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppUiState())
    }

    private fun handleSessionExpired() {
        viewModelScope.launch {
            prefs.setCurrentUserId(null)
            _authState.value = AuthUiState(errorKey = "session_expired")
        }
    }

    fun selectLanguage(language: AppLanguage) {
        viewModelScope.launch { prefs.setLanguage(language) }
    }

    fun setAuthMode(isSignUp: Boolean) {
        _authState.update {
            it.copy(
                isSignUpMode = isSignUp,
                pinInput = "",
                confirmPinInput = "",
                errorKey = if (it.lockoutRemainingSeconds > 0) "account_locked" else null,
                pinError = null
            )
        }
    }

    fun updatePhoneInput(phone: String) {
        _authState.update {
            it.copy(
                phoneInput = phone,
                errorKey = if (it.lockoutRemainingSeconds > 0) "account_locked" else null
            )
        }
    }

    fun updatePinInput(pin: String) {
        val digits = pin.filter { it.isDigit() }.take(PinValidator.MAX_LENGTH)
        _authState.update {
            it.copy(
                pinInput = digits,
                pinError = null,
                errorKey = if (it.lockoutRemainingSeconds > 0) "account_locked" else null
            )
        }
    }

    fun updateConfirmPinInput(pin: String) {
        val digits = pin.filter { it.isDigit() }.take(PinValidator.MAX_LENGTH)
        _authState.update {
            it.copy(
                confirmPinInput = digits,
                pinError = null,
                errorKey = if (it.lockoutRemainingSeconds > 0) "account_locked" else null
            )
        }
    }

    fun submitAuth() {
        val current = _authState.value
        if (current.isLoading) return

        if (!PhoneValidator.isValidMoroccanPhone(current.phoneInput)) {
            _authState.update { it.copy(errorKey = "invalid_phone") }
            return
        }

        if (current.isSignUpMode) {
            val pinErr = PinValidator.validateWithConfirmation(current.pinInput, current.confirmPinInput)
            if (pinErr != null) {
                val errKey = mapPinErrorToKey(pinErr)
                _authState.update { it.copy(errorKey = errKey, pinError = pinErr) }
                return
            }
            viewModelScope.launch {
                _authState.update { it.copy(isLoading = true, errorKey = null, pinError = null) }
                val res = repository.signUpWithPhoneAndPin(current.phoneInput, current.pinInput, current.confirmPinInput)
                handleAuthResult(res)
            }
        } else {
            if (current.lockoutRemainingSeconds > 0) {
                _authState.update { it.copy(errorKey = "account_locked") }
                return
            }
            viewModelScope.launch {
                val remLock = authClient.lockoutManager.remainingLockoutSeconds()
                if (remLock > 0) {
                    _authState.update { it.copy(lockoutRemainingSeconds = remLock, errorKey = "account_locked") }
                    startLockoutTicker(remLock)
                    return@launch
                }
                if (current.pinInput.length < PinValidator.MIN_LENGTH || current.pinInput.any { !it.isDigit() }) {
                    val state = authClient.lockoutManager.recordFailure()
                    if (state.lockoutUntilMillis > 0L) {
                        val rem = authClient.lockoutManager.remainingLockoutSeconds()
                        _authState.update { it.copy(lockoutRemainingSeconds = rem, errorKey = "account_locked") }
                        startLockoutTicker(rem)
                    } else {
                        _authState.update { it.copy(errorKey = "invalid_credentials") }
                    }
                    return@launch
                }
                _authState.update { it.copy(isLoading = true, errorKey = null, pinError = null) }
                val res = repository.loginWithPhoneAndPin(current.phoneInput, current.pinInput)
                handleAuthResult(res)
            }
        }
    }

    private suspend fun handleAuthResult(res: Result<PinAuthOutcome>) {
        if (res.isFailure) {
            val ex = res.exceptionOrNull()
            val errKey = mapAuthErrorToKey(ex)
            val authEx = ex as? AuthException
            val lockSec = if (authEx?.code == AuthErrorCode.ACCOUNT_LOCKED) {
                authEx.lockoutRemainingSeconds.coerceAtLeast(authClient.lockoutManager.remainingLockoutSeconds())
            } else {
                0
            }
            _authState.update {
                it.copy(
                    isLoading = false,
                    errorKey = errKey,
                    pinError = authEx?.pinError,
                    lockoutRemainingSeconds = lockSec
                )
            }
            if (lockSec > 0) startLockoutTicker(lockSec)
            return
        }

        lockoutTickerJob?.cancel()
        when (val outcome = res.getOrNull()) {
            is PinAuthOutcome.AuthenticatedExisting -> {
                prefs.setCurrentUserId(outcome.profile.id)
                _authState.value = AuthUiState()
                if (supabaseRepo.isRemoteConfigured()) {
                    fetchOffersPageInternal(page = 0)
                }
            }
            is PinAuthOutcome.NeedsProfileSetup -> {
                _authState.update {
                    it.copy(
                        isLoading = false,
                        pinInput = "",
                        confirmPinInput = "",
                        pendingPhoneForRegistration = outcome.normalizedPhone,
                        pendingUserIdForRegistration = outcome.userId
                    )
                }
            }
            null -> {
                _authState.update { it.copy(isLoading = false, errorKey = "invalid_credentials") }
            }
        }
    }

    private fun startLockoutTicker(initialSeconds: Int) {
        lockoutTickerJob?.cancel()
        if (initialSeconds <= 0) return
        lockoutTickerJob = viewModelScope.launch {
            var remaining = initialSeconds
            while (remaining > 0) {
                delay(1000L)
                remaining = authClient.lockoutManager.remainingLockoutSeconds()
                _authState.update {
                    it.copy(
                        lockoutRemainingSeconds = remaining,
                        errorKey = if (remaining > 0) "account_locked" else null
                    )
                }
            }
        }
    }

    fun changePin(
        currentPin: String,
        newPin: String,
        confirmNewPin: String,
        onResult: (Result<String?>) -> Unit
    ) {
        val user = uiState.value.currentUser ?: return
        viewModelScope.launch {
            val res = repository.changePin(user.id, user.phone, currentPin, newPin, confirmNewPin)
            if (res.isSuccess) {
                onResult(Result.success(null))
            } else {
                val errKey = mapAuthErrorToKey(res.exceptionOrNull())
                onResult(Result.failure(AuthException(AuthErrorCode.SERVER_ERROR, errKey)))
            }
        }
    }

    fun adminResetUserPin(
        targetUserId: String,
        temporaryPin: String,
        onResult: (Result<String?>) -> Unit
    ) {
        viewModelScope.launch {
            val res = repository.adminResetUserPin(targetUserId, temporaryPin)
            if (res.isSuccess) {
                onResult(Result.success(null))
            } else {
                val errKey = mapAuthErrorToKey(res.exceptionOrNull())
                onResult(Result.failure(AuthException(AuthErrorCode.SERVER_ERROR, errKey)))
            }
        }
    }

    private fun mapPinErrorToKey(pinError: PinValidationError): String = when (pinError) {
        PinValidationError.TOO_SHORT, PinValidationError.NON_DIGITS -> "pin_too_short"
        PinValidationError.WEAK_REPEATED -> "pin_weak_repeated"
        PinValidationError.WEAK_SEQUENCE -> "pin_weak_sequence"
        PinValidationError.MISMATCH -> "pin_mismatch"
    }

    private fun mapAuthErrorToKey(err: Throwable?): String {
        val authErr = err as? AuthException ?: return "error_generic"
        return when (authErr.code) {
            AuthErrorCode.INVALID_PHONE -> "invalid_phone"
            AuthErrorCode.WEAK_PIN -> authErr.pinError?.let { mapPinErrorToKey(it) } ?: "pin_too_short"
            AuthErrorCode.PIN_MISMATCH -> "pin_mismatch"
            AuthErrorCode.INVALID_CREDENTIALS -> "invalid_credentials"
            AuthErrorCode.PHONE_ALREADY_REGISTERED -> "phone_already_registered"
            AuthErrorCode.WRONG_CURRENT_PIN -> "wrong_current_pin"
            AuthErrorCode.ACCOUNT_LOCKED -> "account_locked"
            AuthErrorCode.RATE_LIMITED -> "rate_limited"
            AuthErrorCode.NO_NETWORK -> "no_network"
            AuthErrorCode.SESSION_EXPIRED -> "session_expired"
            AuthErrorCode.NOT_CONFIGURED -> "not_configured"
            AuthErrorCode.FORBIDDEN -> "forbidden"
            AuthErrorCode.SERVER_ERROR -> "error_generic"
        }
    }

    fun loginDemoUser(userId: String) {
        if (!SupabaseConfig.demoMode) return
        viewModelScope.launch {
            prefs.setCurrentUserId(userId)
            _authState.value = AuthUiState()
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            prefs.setCurrentUserId(null)
            _authState.value = AuthUiState()
        }
    }

    fun completeProfileRegistration(
        fullName: String,
        role: String,
        regionId: String,
        city: String,
        farmOrCoopName: String,
        businessName: String,
        businessType: String,
        selectedCategoryIds: List<String>,
        verificationDocPath: String = ""
    ) {
        val phone = _authState.value.pendingPhoneForRegistration ?: uiState.value.currentUser?.phone ?: return
        val existingId = _authState.value.pendingUserIdForRegistration ?: uiState.value.currentUser?.id
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch {
            val res = repository.registerOrUpdateProfile(
                existingId = existingId,
                phone = phone,
                fullName = fullName,
                role = role,
                regionId = regionId,
                city = city,
                farmOrCoopName = farmOrCoopName,
                businessName = businessName,
                businessType = businessType,
                selectedCategoryIds = selectedCategoryIds,
                verificationDocPath = verificationDocPath,
                isOnline = online
            )
            res.onSuccess { profile ->
                prefs.setCurrentUserId(profile.id)
                _authState.value = AuthUiState()
                if (verificationDocPath.startsWith("/")) {
                    mediaSyncManager.enqueueVerificationDocUpload(profile.id, profile.id, verificationDocPath)
                }
            }.onFailure { err ->
                _syncErrorMessage.value = err.message
            }
        }
    }

    fun uploadVerificationDoc(localDocPath: String) {
        val user = uiState.value.currentUser ?: return
        viewModelScope.launch {
            db.dao().updateProfileVerificationDoc(user.id, localDocPath)
            mediaSyncManager.enqueueVerificationDocUpload(user.id, user.id, localDocPath)
        }
    }

    fun cancelRegistration() {
        _authState.update {
            it.copy(
                pendingPhoneForRegistration = null,
                pendingUserIdForRegistration = null,
                pinInput = "",
                confirmPinInput = ""
            )
        }
    }

    // Offers
    suspend fun compressPickedImage(uri: Uri): String? = ImageCompressor.compressAndSave(getApplication(), uri)

    fun saveOffer(
        editingOfferId: String?,
        product: ProductEntity,
        city: String,
        regionId: String,
        quantity: Double,
        unit: String,
        askingPrice: Double,
        readyDateEpochDay: Long,
        attributesJson: String,
        photoPaths: List<String>,
        voicePath: String,
        voiceDuration: Int
    ) {
        val user = uiState.value.currentUser ?: return
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch {
            val finalPhotos = if (photoPaths.isEmpty()) "drawable:${product.imageKey}" else photoPaths.joinToString(",")
            val hasLocalMedia = photoPaths.any { it.startsWith("/") } || voicePath.startsWith("/")
            val initialMediaStatus = if (supabaseRepo.isRemoteConfigured() && hasLocalMedia) {
                MediaStatus.UPLOADING
            } else {
                MediaStatus.READY
            }
            val offer = OfferEntity(
                id = editingOfferId ?: UUID.randomUUID().toString(),
                farmerId = user.id,
                productId = product.id,
                categoryId = product.categoryId,
                regionId = regionId,
                city = city,
                quantity = quantity,
                unit = unit,
                askingPrice = askingPrice,
                readyDateEpochDay = readyDateEpochDay,
                attributesJson = attributesJson,
                photoUrisCsv = finalPhotos,
                voiceNotePath = voicePath,
                voiceDurationSec = voiceDuration,
                mediaStatus = initialMediaStatus,
                status = OfferStatus.ACTIVE
            )
            repository.createOrUpdateOffer(offer, online).onSuccess { saved ->
                if (hasLocalMedia) {
                    mediaSyncManager.enqueueOfferMediaUpload(saved.id, user.id, photoPaths, voicePath)
                }
            }
        }
    }

    fun retryOfferMediaUpload(offer: OfferEntity) {
        viewModelScope.launch {
            db.dao().updateOfferMediaStatus(offer.id, MediaStatus.UPLOADING)
            val photos = offer.photoUrisCsv.split(",").filter { it.isNotBlank() }
            mediaSyncManager.enqueueOfferMediaUpload(offer.id, offer.farmerId, photos, offer.voiceNotePath)
        }
    }

    fun toggleVoicePlayback(voicePathOrToken: String) {
        if (voicePathOrToken.startsWith("/") || !supabaseRepo.isRemoteConfigured()) {
            voiceManager.togglePlayback(voicePathOrToken)
            return
        }
        viewModelScope.launch {
            val resolved = mediaCache.resolveLocalOrCachedFile("voice-notes", voicePathOrToken)
            if (resolved != null) {
                voiceManager.togglePlayback(resolved.absolutePath)
            }
        }
    }

    fun setOfferStatus(offerId: String, status: String) {
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch { repository.updateOfferStatus(offerId, status, online) }
    }

    fun duplicateOffer(offer: OfferEntity) {
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch { repository.duplicateOffer(offer, online) }
    }

    fun submitProductProposal(name: String, categoryId: String, unit: String, notes: String) {
        val uid = uiState.value.currentUser?.id ?: return
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch { repository.submitProductProposal(uid, name, categoryId, unit, notes, online) }
    }

    // Filters, Pagination & Saved Filters
    fun updateFilter(transform: (MarketplaceFilter) -> MarketplaceFilter) {
        _filterState.update(transform)
        if (supabaseRepo.isRemoteConfigured() && uiState.value.isEffectivelyOnline) {
            viewModelScope.launch { fetchOffersPageInternal(page = 0) }
        }
    }

    fun clearFilters() {
        _filterState.value = MarketplaceFilter()
        if (supabaseRepo.isRemoteConfigured() && uiState.value.isEffectivelyOnline) {
            viewModelScope.launch { fetchOffersPageInternal(page = 0) }
        }
    }

    fun loadMoreOffers() {
        val pag = _paginationState.value
        if (pag.isLoadingMore || !pag.hasMore || !uiState.value.isEffectivelyOnline) return
        viewModelScope.launch {
            fetchOffersPageInternal(page = pag.page + 1)
        }
    }

    private suspend fun fetchOffersPageInternal(page: Int) {
        _paginationState.update { it.copy(isLoadingMore = true) }
        val flt = _filterState.value
        val sortCol = when (flt.sortBy) {
            OfferSortOption.PRICE_LOW -> "asking_price.asc"
            else -> "created_at.desc"
        }
        val res = repository.fetchOffersPage(
            OfferQueryFilter(
                categoryId = flt.categoryId,
                productId = flt.productId,
                city = flt.city,
                maxPrice = flt.maxPrice,
                minQty = flt.minQty,
                sortOrder = sortCol,
                page = page,
                pageSize = 20
            )
        )
        res.onSuccess { pageRes ->
            _paginationState.value = PaginationUiState(
                page = page,
                hasMore = pageRes.hasMore,
                isLoadingMore = false
            )
        }.onFailure {
            _paginationState.update { it.copy(isLoadingMore = false) }
        }
    }

    fun saveCurrentFilter(label: String) {
        val uid = uiState.value.currentUser?.id ?: return
        val flt = _filterState.value
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch {
            repository.saveFilter(uid, label, flt.categoryId, flt.productId, flt.city, flt.maxPrice, flt.minQty, online)
        }
    }

    fun applySavedFilter(saved: SavedFilterEntity) {
        _filterState.value = MarketplaceFilter(
            categoryId = saved.categoryId,
            productId = saved.productId,
            city = saved.city,
            maxPrice = saved.maxPrice,
            minQty = saved.minQty
        )
        if (supabaseRepo.isRemoteConfigured() && uiState.value.isEffectivelyOnline) {
            viewModelScope.launch { fetchOffersPageInternal(page = 0) }
        }
    }

    fun deleteSavedFilter(filterId: String) {
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch { repository.deleteSavedFilter(filterId, online) }
    }

    fun toggleFavoriteOffer(offerId: String) {
        val userId = uiState.value.currentUser?.id ?: return
        viewModelScope.launch { repository.toggleFavoriteOffer(userId, offerId) }
    }

    // Buy Requests & Deals
    fun postBuyRequest(product: ProductEntity, city: String, regionId: String, qty: Double, unit: String, maxPrice: Double, neededDay: Long, notes: String) {
        val user = uiState.value.currentUser ?: return
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch {
            repository.createBuyRequest(
                BuyRequestEntity(
                    id = UUID.randomUUID().toString(),
                    buyerId = user.id,
                    productId = product.id,
                    categoryId = product.categoryId,
                    regionId = regionId,
                    city = city,
                    quantity = qty,
                    unit = unit,
                    maxPrice = maxPrice,
                    neededDateEpochDay = neededDay,
                    notes = notes
                ),
                isOnline = online
            )
        }
    }

    fun sendDealFromOffer(offer: OfferEntity, qty: Double, offeredPrice: Double, deliveryType: String, preferredDay: Long, note: String) {
        val buyer = uiState.value.currentUser ?: return
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch {
            repository.createDealRequest(
                offerId = offer.id,
                buyRequestId = null,
                farmerId = offer.farmerId,
                buyerId = buyer.id,
                productId = offer.productId,
                quantity = qty,
                unit = offer.unit,
                proposedPrice = offeredPrice,
                deliveryType = deliveryType,
                preferredDateEpochDay = preferredDay,
                note = note,
                initiatorRole = UserRole.BUYER,
                isOnline = online
            )
        }
    }

    fun respondToWantedPost(req: BuyRequestEntity, proposedPrice: Double, deliveryType: String, note: String) {
        val farmer = uiState.value.currentUser ?: return
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch {
            repository.createDealRequest(
                offerId = null,
                buyRequestId = req.id,
                farmerId = farmer.id,
                buyerId = req.buyerId,
                productId = req.productId,
                quantity = req.quantity,
                unit = req.unit,
                proposedPrice = proposedPrice,
                deliveryType = deliveryType,
                preferredDateEpochDay = req.neededDateEpochDay,
                note = note,
                initiatorRole = UserRole.FARMER,
                isOnline = online
            )
        }
    }

    fun counterDeal(deal: DealEntity, newPrice: Double) {
        val user = uiState.value.currentUser ?: return
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch {
            _dealActionError.value = null
            repository.counterDeal(deal, newPrice, user.id, user.role, online).onFailure { err ->
                _dealActionError.value = err.message
            }
        }
    }

    fun acceptDeal(deal: DealEntity) {
        val user = uiState.value.currentUser ?: return
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch {
            _dealActionError.value = null
            repository.acceptDeal(deal, user.id, user.role, online).onFailure { err ->
                _dealActionError.value = err.message
            }
        }
    }

    fun cancelDeal(deal: DealEntity) {
        val user = uiState.value.currentUser ?: return
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch {
            _dealActionError.value = null
            repository.cancelDeal(deal, user.id, user.role, online).onFailure { err ->
                _dealActionError.value = err.message
            }
        }
    }

    fun confirmDealCompletion(deal: DealEntity) {
        val user = uiState.value.currentUser ?: return
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch {
            _dealActionError.value = null
            repository.confirmDealCompletion(deal, user.id, user.role, online).onFailure { err ->
                _dealActionError.value = err.message
            }
        }
    }

    fun clearDealActionError() {
        _dealActionError.value = null
    }

    fun rateDealPartner(deal: DealEntity, stars: Int, comment: String) {
        val user = uiState.value.currentUser ?: return
        val target = if (user.id == deal.farmerId) deal.buyerId else deal.farmerId
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch { repository.submitRating(deal.id, user.id, target, stars, comment, online) }
    }

    fun reportTarget(targetType: String, targetId: String, reason: String) {
        val user = uiState.value.currentUser ?: return
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch { repository.submitReport(user.id, targetType, targetId, reason, online) }
    }

    fun markNotificationsRead() {
        val uid = uiState.value.currentUser?.id ?: return
        val online = uiState.value.isEffectivelyOnline
        viewModelScope.launch { repository.markAllNotificationsRead(uid, online) }
    }

    // Offline & Sync
    fun toggleSimulateOffline(offline: Boolean) {
        viewModelScope.launch { prefs.setSimulateOffline(offline) }
    }

    fun syncNow() {
        if (!uiState.value.isEffectivelyOnline) return
        viewModelScope.launch {
            val summary = repository.syncPendingItemsNow()
            _syncErrorMessage.value = if (summary.failedCount > 0) "${summary.failedCount} failed" else null
        }
    }

    fun retryFailedSync() {
        if (!uiState.value.isEffectivelyOnline) return
        viewModelScope.launch {
            val summary = repository.retryFailedSyncItems()
            _syncErrorMessage.value = if (summary.failedCount > 0) "${summary.failedCount} failed" else null
        }
    }

    // Admin
    fun approveUser(userId: String) {
        viewModelScope.launch {
            repository.setVerificationStatus(userId, VerificationStatus.VERIFIED).onFailure { err ->
                _syncErrorMessage.value = err.message
            }
        }
    }

    fun rejectUser(userId: String, reason: String) {
        viewModelScope.launch {
            repository.setVerificationStatus(userId, VerificationStatus.REJECTED, reason).onFailure { err ->
                _syncErrorMessage.value = err.message
            }
        }
    }

    fun adminAddProduct(categoryId: String, nameDarija: String, nameAr: String, nameFr: String, unitsCsv: String, schemaJson: String) {
        viewModelScope.launch {
            repository.addProductToCatalog(categoryId, nameDarija, nameAr, nameFr, unitsCsv, schemaJson).onFailure { err ->
                _syncErrorMessage.value = err.message
            }
        }
    }

    fun adminSetReferencePrice(productId: String, regionId: String, unit: String, priceMad: Double) {
        viewModelScope.launch {
            repository.addReferencePrice(productId, regionId, unit, priceMad).onFailure { err ->
                _syncErrorMessage.value = err.message
            }
        }
    }

    fun adminApproveProposal(proposal: ProductProposalEntity) {
        viewModelScope.launch {
            repository.approveProposal(proposal).onFailure { err ->
                _syncErrorMessage.value = err.message
            }
        }
    }

    fun adminRejectProposal(proposal: ProductProposalEntity) {
        viewModelScope.launch {
            repository.rejectProposal(proposal).onFailure { err ->
                _syncErrorMessage.value = err.message
            }
        }
    }

    fun adminResolveReport(report: ReportEntity) {
        viewModelScope.launch {
            repository.resolveReport(report).onFailure { err ->
                _syncErrorMessage.value = err.message
            }
        }
    }

    fun clearSyncError() {
        _syncErrorMessage.value = null
    }

    fun refreshMarketSummary(productId: String, regionId: String? = null, preferredUnit: String? = null) {
        if (!supabaseRepo.isRemoteConfigured() || !uiState.value.isEffectivelyOnline) return
        val cacheKey = "$productId|${regionId.orEmpty()}|${preferredUnit.orEmpty()}"
        viewModelScope.launch {
            val remote = repository.fetchRemoteMarketSummary(productId, regionId, preferredUnit)
            if (remote != null && (remote.sampleCount > 0 || remote.wholesaleReferencePrice != null)) {
                _remoteMarketSummaries.update { it + (cacheKey to remote) }
            }
        }
    }

    fun getMarketSummary(productId: String, regionId: String? = null, preferredUnit: String? = null): ProductMarketSummary {
        val state = uiState.value
        val cacheKey = "$productId|${regionId.orEmpty()}|${preferredUnit.orEmpty()}"
        val remote = state.remoteMarketSummaries[cacheKey]
        if (remote != null && (remote.sampleCount > 0 || remote.wholesaleReferencePrice != null)) {
            return remote
        }
        return PriceAnalytics.computeMarketSummary(
            productId = productId,
            regionId = regionId,
            deals = state.deals,
            offers = state.offers,
            referencePrices = state.referencePrices,
            todayEpochDay = LocalDate.now().toEpochDay(),
            preferredUnit = preferredUnit
        )
    }

    override fun onCleared() {
        super.onCleared()
        notificationSource.stop()
        voiceManager.release()
    }

    private data class PaginationUiState(
        val page: Int = 0,
        val hasMore: Boolean = false,
        val isLoadingMore: Boolean = false
    )

    private data class SessionBundle(
        val loaded: Boolean,
        val lang: AppLanguage,
        val hasLang: Boolean,
        val user: ProfileEntity?,
        val auth: AuthUiState
    )

    private data class NetBundle(
        val isConnected: Boolean,
        val simulateOffline: Boolean,
        val pendingCount: Int,
        val failedItems: List<SyncQueueEntity>,
        val syncError: String?
    )

    private data class CatalogBundle(
        val regions: List<RegionEntity>,
        val categories: List<CategoryEntity>,
        val products: List<ProductEntity>,
        val profiles: List<ProfileEntity>,
        val farmerDetails: List<FarmerDetailsEntity>
    )

    private data class MarketBundle(
        val buyerDetails: List<BuyerDetailsEntity>,
        val offers: List<OfferEntity>,
        val buyRequests: List<BuyRequestEntity>,
        val deals: List<DealEntity>,
        val dealHistory: List<DealStatusHistoryEntity>
    )

    private data class ExtraBundle(
        val ratings: List<RatingEntity>,
        val notifications: List<NotificationEntity>,
        val savedFilters: List<SavedFilterEntity>,
        val referencePrices: List<ReferencePriceEntity>,
        val proposals: List<ProductProposalEntity>,
        val favoriteOfferIds: Set<String>
    )

    private data class MetaBundle(
        val pagination: PaginationUiState,
        val dealActionError: String?,
        val summaries: Map<String, ProductMarketSummary>,
        val reports: List<ReportEntity>,
        val filter: MarketplaceFilter
    )
}
