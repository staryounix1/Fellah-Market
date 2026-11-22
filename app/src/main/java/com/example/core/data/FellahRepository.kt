package com.example.core.data

import com.example.core.auth.AuthErrorCode
import com.example.core.auth.AuthException
import com.example.core.auth.SessionManager
import com.example.core.auth.SupabaseAuthClient
import com.example.core.domain.DealStateMachine
import com.example.core.domain.PhoneValidator
import com.example.core.domain.PinValidationError
import com.example.core.domain.PinValidator
import com.example.core.domain.PriceAnalytics
import com.example.core.domain.ProductMarketSummary
import com.example.core.network.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.UUID

sealed class PinAuthOutcome {
    data class AuthenticatedExisting(val profile: ProfileEntity) : PinAuthOutcome()
    data class NeedsProfileSetup(val userId: String?, val normalizedPhone: String) : PinAuthOutcome()
}

class FellahRepository(
    private val dao: FellahDao,
    private val authClient: SupabaseAuthClient,
    private val sessionManager: SessionManager,
    private val supabaseRepo: SupabaseRepository,
    private val notificationPort: NotificationChannelPort,
    private val prefsStore: PreferencesStore? = null,
    private val isDemoModeProvider: () -> Boolean = { SupabaseConfig.demoMode }
) {
    private val demoCredsMoshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val demoCredsMapType = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    private val demoCredsAdapter = demoCredsMoshi.adapter<Map<String, String>>(demoCredsMapType)
    private val secureRandom = SecureRandom()

    val allProfiles: Flow<List<ProfileEntity>> = dao.observeAllProfiles()
    val allFarmerDetails: Flow<List<FarmerDetailsEntity>> = dao.observeAllFarmerDetails()
    val allBuyerDetails: Flow<List<BuyerDetailsEntity>> = dao.observeAllBuyerDetails()
    val regions: Flow<List<RegionEntity>> = dao.observeRegions()
    val categories: Flow<List<CategoryEntity>> = dao.observeCategories()
    val products: Flow<List<ProductEntity>> = dao.observeProducts()
    val offers: Flow<List<OfferEntity>> = dao.observeAllOffers()
    val buyRequests: Flow<List<BuyRequestEntity>> = dao.observeAllBuyRequests()
    val deals: Flow<List<DealEntity>> = dao.observeAllDeals()
    val dealHistory: Flow<List<DealStatusHistoryEntity>> = dao.observeAllDealHistory()
    val ratings: Flow<List<RatingEntity>> = dao.observeAllRatings()
    val referencePrices: Flow<List<ReferencePriceEntity>> = dao.observeReferencePrices()
    val productProposals: Flow<List<ProductProposalEntity>> = dao.observeProductProposals()
    val reports: Flow<List<ReportEntity>> = dao.observeReports()
    val pendingSyncQueue: Flow<List<SyncQueueEntity>> = dao.observePendingSyncQueue()
    val failedSyncQueue: Flow<List<SyncQueueEntity>> = dao.observeFailedSyncQueue()
    val analyticsEvents: Flow<List<AnalyticsEventEntity>> = dao.observeAnalyticsEvents()

    private fun useRemoteBackend(): Boolean {
        if (!supabaseRepo.isRemoteConfigured()) return false
        return !isDemoModeProvider() || sessionManager.getSession() != null
    }

    fun observeProfile(userId: String): Flow<ProfileEntity?> = dao.observeProfile(userId)
    fun observeNotifications(userId: String): Flow<List<NotificationEntity>> = dao.observeUserNotifications(userId)
    fun observeSavedFilters(buyerId: String): Flow<List<SavedFilterEntity>> = dao.observeSavedFilters(buyerId)
    fun observeFavoriteOffers(userId: String): Flow<List<FavoriteOfferEntity>> = dao.observeFavoriteOffers(userId)

    suspend fun toggleFavoriteOffer(userId: String, offerId: String) {
        if (dao.isFavoriteOffer(userId, offerId)) dao.deleteFavoriteOffer(userId, offerId)
        else dao.upsertFavoriteOffer(FavoriteOfferEntity(userId = userId, offerId = offerId))
    }

    suspend fun ensureSeededOrRefreshed(currentUserId: String?, isAdmin: Boolean) {
        if (supabaseRepo.isRemoteConfigured() && sessionManager.getSession() != null && currentUserId != null) {
            supabaseRepo.refreshAllUserData(currentUserId, isAdmin)
        } else if (isDemoModeProvider()) {
            SeedData.seedIfEmpty(dao)
        }
    }

    suspend fun refreshFromServer(currentUserId: String, isAdmin: Boolean): Result<Unit> {
        if (!useRemoteBackend()) return Result.success(Unit)
        return supabaseRepo.refreshAllUserData(currentUserId, isAdmin)
    }

    suspend fun fetchOffersPage(filter: OfferQueryFilter): Result<PaginatedResult<OfferEntity>> {
        if (!useRemoteBackend()) {
            return Result.success(PaginatedResult(emptyList(), hasMore = false, totalCount = null))
        }
        return supabaseRepo.fetchOffersPage(filter)
    }

    suspend fun signUpWithPhoneAndPin(
        rawPhone: String,
        pin: String,
        confirmPin: String
    ): Result<PinAuthOutcome> {
        val signUpRes = authClient.signUpWithPhoneAndPin(rawPhone, pin, confirmPin)
        if (signUpRes.isFailure) {
            return Result.failure(signUpRes.exceptionOrNull() ?: AuthException(AuthErrorCode.SERVER_ERROR))
        }
        val session = signUpRes.getOrNull()
        val normalizedLocal = PhoneValidator.normalizeMoroccanPhone(rawPhone)

        if (session != null && supabaseRepo.isRemoteConfigured()) {
            supabaseRepo.fetchProfileById(session.userId)
            supabaseRepo.refreshCatalog()
            return Result.success(PinAuthOutcome.NeedsProfileSetup(session.userId, normalizedLocal))
        }

        // Local DEMO_MODE fallback when remote Supabase is not configured
        val existing = dao.getProfileByPhone(normalizedLocal)
        if (existing != null) {
            return Result.failure(AuthException(AuthErrorCode.PHONE_ALREADY_REGISTERED))
        }
        saveDemoPinHash(normalizedLocal, pin)
        return Result.success(PinAuthOutcome.NeedsProfileSetup(null, normalizedLocal))
    }

    suspend fun loginWithPhoneAndPin(rawPhone: String, pin: String): Result<PinAuthOutcome> {
        val loginRes = authClient.loginWithPhoneAndPin(rawPhone, pin)
        if (loginRes.isFailure) {
            return Result.failure(loginRes.exceptionOrNull() ?: AuthException(AuthErrorCode.INVALID_CREDENTIALS))
        }
        val session = loginRes.getOrNull()
        val normalizedLocal = PhoneValidator.normalizeMoroccanPhone(rawPhone)

        if (session != null && supabaseRepo.isRemoteConfigured()) {
            val remoteProfile = supabaseRepo.fetchProfileById(session.userId).getOrNull()
            supabaseRepo.refreshCatalog()
            return if (remoteProfile != null && remoteProfile.fullName.isNotBlank()) {
                supabaseRepo.refreshAllUserData(remoteProfile.id, remoteProfile.role == UserRole.ADMIN)
                Result.success(PinAuthOutcome.AuthenticatedExisting(remoteProfile))
            } else {
                Result.success(PinAuthOutcome.NeedsProfileSetup(session.userId, normalizedLocal))
            }
        }

        // Local DEMO_MODE fallback
        val existing = dao.getProfileByPhone(normalizedLocal)
        val verified = existing != null && verifyDemoPinHash(normalizedLocal, pin)
        if (!verified) {
            val state = authClient.lockoutManager.recordFailure()
            return Result.failure(
                if (state.lockoutUntilMillis > 0L) {
                    AuthException(
                        AuthErrorCode.ACCOUNT_LOCKED,
                        lockoutRemainingSeconds = authClient.lockoutManager.remainingLockoutSeconds()
                    )
                } else {
                    AuthException(AuthErrorCode.INVALID_CREDENTIALS)
                }
            )
        }
        authClient.lockoutManager.recordSuccess()
        return Result.success(PinAuthOutcome.AuthenticatedExisting(existing!!))
    }

    suspend fun changePin(
        userId: String,
        rawPhone: String,
        currentPin: String,
        newPin: String,
        confirmNewPin: String
    ): Result<Unit> {
        if (useRemoteBackend()) {
            val res = authClient.changePin(currentPin, newPin, confirmNewPin)
            if (res.isFailure) return res
            val clearRes = supabaseRepo.clearMustChangePin(userId)
            return if (clearRes.isFailure) clearRes else Result.success(Unit)
        }

        // Local DEMO_MODE fallback
        val pinErr = PinValidator.validateWithConfirmation(newPin, confirmNewPin)
        if (pinErr != null) {
            val code = if (pinErr == PinValidationError.MISMATCH) AuthErrorCode.PIN_MISMATCH else AuthErrorCode.WEAK_PIN
            return Result.failure(AuthException(code, pinError = pinErr))
        }
        val normPhone = PhoneValidator.normalizeMoroccanPhone(rawPhone)
        if (!verifyDemoPinHash(normPhone, currentPin)) {
            return Result.failure(AuthException(AuthErrorCode.WRONG_CURRENT_PIN))
        }
        saveDemoPinHash(normPhone, newPin)
        dao.updateMustChangePin(userId, false)
        return Result.success(Unit)
    }

    suspend fun adminResetUserPin(targetUserId: String, temporaryPin: String): Result<Unit> {
        if (useRemoteBackend()) {
            return supabaseRepo.adminResetUserPin(targetUserId, temporaryPin)
        }
        val pinErr = PinValidator.validate(temporaryPin)
        if (pinErr != null) {
            return Result.failure(AuthException(AuthErrorCode.WEAK_PIN, pinError = pinErr))
        }
        val target = dao.getProfileById(targetUserId) ?: return Result.failure(AuthException(AuthErrorCode.SERVER_ERROR))
        saveDemoPinHash(PhoneValidator.normalizeMoroccanPhone(target.phone), temporaryPin)
        dao.updateMustChangePin(targetUserId, true)
        return Result.success(Unit)
    }

    private suspend fun readDemoCredsMap(): MutableMap<String, String> {
        val raw = prefsStore?.getDemoCredentialsJson() ?: return mutableMapOf()
        return runCatching { demoCredsAdapter.fromJson(raw)?.toMutableMap() }.getOrNull() ?: mutableMapOf()
    }

    private suspend fun saveDemoPinHash(normalizedPhone: String, pin: String) {
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hashHex = sha256Hex(saltHex, pin)
        val map = readDemoCredsMap()
        map[normalizedPhone] = "$saltHex:$hashHex"
        prefsStore?.setDemoCredentialsJson(demoCredsAdapter.toJson(map))
    }

    private suspend fun verifyDemoPinHash(normalizedPhone: String, candidatePin: String): Boolean {
        val stored = readDemoCredsMap()[normalizedPhone]
        if (stored == null) {
            // Seeded quick demo profiles accept default QA demo PIN 258369 until changed/reset
            return candidatePin == "258369"
        }
        val parts = stored.split(":", limit = 2)
        if (parts.size != 2) return false
        val expected = sha256Hex(parts[0], candidatePin)
        return constantTimeEquals(parts[1], expected)
    }

    private fun sha256Hex(saltHex: String, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$saltHex:$pin".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    suspend fun logout() {
        authClient.logout()
    }

    suspend fun registerOrUpdateProfile(
        existingId: String?,
        phone: String,
        fullName: String,
        role: String,
        regionId: String,
        city: String,
        farmOrCoopName: String,
        businessName: String,
        businessType: String,
        selectedCategoryIds: List<String>,
        verificationDocPath: String = "",
        isOnline: Boolean = true
    ): Result<ProfileEntity> {
        val normalizedPhone = PhoneValidator.normalizeMoroccanPhone(phone)
        val sessionUid = sessionManager.getSession()?.userId
        val targetId = existingId ?: sessionUid ?: UUID.randomUUID().toString()
        val existing = dao.getProfileById(targetId)
        val safeRole = if (role in setOf(UserRole.FARMER, UserRole.BUYER)) role else UserRole.FARMER

        val profile = ProfileEntity(
            id = targetId,
            phone = if (useRemoteBackend()) PhoneValidator.toE164Moroccan(normalizedPhone) else normalizedPhone,
            fullName = fullName.trim(),
            role = existing?.role?.takeIf { it == UserRole.ADMIN } ?: safeRole,
            verificationStatus = existing?.verificationStatus ?: VerificationStatus.PENDING,
            rejectionReason = existing?.rejectionReason ?: "",
            verificationDocPath = verificationDocPath.ifBlank { existing?.verificationDocPath ?: "" },
            regionId = regionId,
            city = city,
            ratingAvg = existing?.ratingAvg ?: 0.0,
            ratingCount = existing?.ratingCount ?: 0,
            updatedAt = System.currentTimeMillis()
        )

        if (useRemoteBackend()) {
            return supabaseRepo.upsertProfileRemote(
                profile = profile,
                farmOrCoopName = farmOrCoopName.trim(),
                businessName = businessName.trim(),
                businessType = businessType,
                categoryIds = selectedCategoryIds,
                isOnline = isOnline
            )
        }

        dao.upsertProfile(profile)
        val catsCsv = selectedCategoryIds.joinToString(",")
        if (profile.role == UserRole.FARMER) {
            dao.upsertFarmerDetails(FarmerDetailsEntity(targetId, farmOrCoopName.trim(), catsCsv))
        } else if (profile.role == UserRole.BUYER) {
            dao.upsertBuyerDetails(BuyerDetailsEntity(targetId, businessName.trim(), businessType, catsCsv))
        }
        return Result.success(profile)
    }

    suspend fun createOrUpdateOffer(
        offer: OfferEntity,
        isOnline: Boolean
    ): Result<OfferEntity> {
        val fixedOffer = offer.copy(
            id = runCatching { UUID.fromString(offer.id).toString() }.getOrElse { UUID.randomUUID().toString() },
            updatedAt = System.currentTimeMillis()
        )
        logAnalytics("offer_created", fixedOffer.farmerId, """{"product_id":"${fixedOffer.productId}","offline":${!isOnline}}""")

        if (useRemoteBackend()) {
            return supabaseRepo.upsertOffer(fixedOffer, isOnline)
        }

        val effectiveSync = if (isOnline) SyncState.SYNCED else SyncState.PENDING_SYNC
        val toSave = fixedOffer.copy(syncState = effectiveSync)
        dao.upsertOffer(toSave)
        if (!isOnline) {
            dao.enqueueSync(
                SyncQueueEntity(
                    id = UUID.randomUUID().toString(),
                    entityType = "offer",
                    entityId = toSave.id,
                    action = "upsert",
                    payloadJson = PostgrestCodec.mapAdapter.toJson(PostgrestCodec.offerToRemoteMap(toSave))
                )
            )
        } else {
            notifyMatchingSavedFilters(toSave)
        }
        return Result.success(toSave)
    }

    private suspend fun notifyMatchingSavedFilters(offer: OfferEntity) {
        val filters = dao.getAllSavedFilters()
        filters.forEach { filter ->
            val catMatch = filter.categoryId == null || filter.categoryId == offer.categoryId
            val prodMatch = filter.productId == null || filter.productId == offer.productId
            val cityMatch = filter.city.isNullOrBlank() || filter.city == offer.city
            val priceMatch = filter.maxPrice == null || offer.askingPrice <= filter.maxPrice
            val qtyMatch = filter.minQty == null || offer.quantity >= filter.minQty
            if (catMatch && prodMatch && cityMatch && priceMatch && qtyMatch) {
                createLocalNotification(
                    userId = filter.buyerId,
                    type = "saved_filter_match",
                    titleDarija = "عرض جديد كيناسب البحث ديالك: ${filter.label}",
                    titleAr = "عرض جديد يطابق بحثك: ${filter.label}",
                    titleFr = "Nouvelle offre pour votre alerte : ${filter.label}",
                    bodyText = "${offer.city} - ${offer.askingPrice} MAD / ${offer.unit}",
                    relatedId = offer.id
                )
            }
        }
    }

    suspend fun updateOfferStatus(offerId: String, status: String, isOnline: Boolean = true) {
        if (useRemoteBackend()) {
            supabaseRepo.updateOfferStatus(offerId, status, isOnline)
            return
        }
        val current = dao.getOfferById(offerId) ?: return
        dao.upsertOffer(current.copy(status = status, updatedAt = System.currentTimeMillis()))
    }

    suspend fun duplicateOffer(offer: OfferEntity, isOnline: Boolean = true) {
        val copy = offer.copy(
            id = UUID.randomUUID().toString(),
            status = OfferStatus.ACTIVE,
            mediaStatus = MediaStatus.READY,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        createOrUpdateOffer(copy, isOnline)
    }

    suspend fun createBuyRequest(request: BuyRequestEntity, isOnline: Boolean) {
        val fixedReq = request.copy(
            id = runCatching { UUID.fromString(request.id).toString() }.getOrElse { UUID.randomUUID().toString() },
            updatedAt = System.currentTimeMillis()
        )
        if (useRemoteBackend()) {
            supabaseRepo.upsertBuyRequest(fixedReq, isOnline)
            return
        }
        val effectiveSync = if (isOnline) SyncState.SYNCED else SyncState.PENDING_SYNC
        val toSave = fixedReq.copy(syncState = effectiveSync)
        dao.upsertBuyRequest(toSave)
        if (!isOnline) {
            dao.enqueueSync(
                SyncQueueEntity(
                    id = UUID.randomUUID().toString(),
                    entityType = "buy_request",
                    entityId = toSave.id,
                    action = "upsert",
                    payloadJson = PostgrestCodec.mapAdapter.toJson(PostgrestCodec.buyRequestToRemoteMap(toSave))
                )
            )
        } else {
            val farmerDetails = dao.observeAllFarmerDetails().first()
            farmerDetails.filter { toSave.categoryId in it.mainCategoryIdsCsv.split(",") }.forEach { fd ->
                createLocalNotification(
                    userId = fd.userId,
                    type = "new_wanted",
                    titleDarija = "طلب جديد من مشتري فـ ${toSave.city}",
                    titleAr = "طلب شراء جديد في ${toSave.city}",
                    titleFr = "Nouveau besoin acheteur à ${toSave.city}",
                    bodyText = "${toSave.quantity} ${toSave.unit} - max ${toSave.maxPrice} MAD",
                    relatedId = toSave.id
                )
            }
        }
    }

    suspend fun createDealRequest(
        offerId: String?,
        buyRequestId: String?,
        farmerId: String,
        buyerId: String,
        productId: String,
        quantity: Double,
        unit: String,
        proposedPrice: Double,
        deliveryType: String,
        preferredDateEpochDay: Long,
        note: String,
        initiatorRole: String,
        isOnline: Boolean
    ) {
        val dealId = UUID.randomUUID().toString()
        val deal = DealEntity(
            id = dealId,
            offerId = offerId,
            buyRequestId = buyRequestId,
            farmerId = farmerId,
            buyerId = buyerId,
            productId = productId,
            quantity = quantity,
            unit = unit,
            proposedPrice = proposedPrice,
            deliveryType = deliveryType,
            preferredDateEpochDay = preferredDateEpochDay,
            note = note,
            status = DealStatus.REQUESTED,
            lastActorRole = initiatorRole,
            syncState = if (isOnline) SyncState.SYNCED else SyncState.PENDING_SYNC
        )
        val actorId = if (initiatorRole == UserRole.BUYER) buyerId else farmerId
        logAnalytics("request_sent", actorId, """{"deal_id":"$dealId","offline":${!isOnline}}""")

        if (useRemoteBackend()) {
            supabaseRepo.createDeal(deal, actorId, isOnline)
            return
        }

        dao.upsertDeal(deal)
        dao.insertDealHistory(
            DealStatusHistoryEntity(
                id = UUID.randomUUID().toString(),
                dealId = dealId,
                status = DealStatus.REQUESTED,
                actorId = actorId,
                note = note.ifBlank { "$quantity $unit @ $proposedPrice MAD" },
                priceAtStep = proposedPrice
            )
        )
        if (!isOnline) {
            dao.enqueueSync(
                SyncQueueEntity(
                    id = UUID.randomUUID().toString(),
                    entityType = "deal",
                    entityId = dealId,
                    action = "upsert",
                    payloadJson = PostgrestCodec.mapAdapter.toJson(PostgrestCodec.dealToRemoteMap(deal))
                )
            )
        }
        val recipientId = if (initiatorRole == UserRole.BUYER) farmerId else buyerId
        createLocalNotification(
            userId = recipientId,
            type = "new_request",
            titleDarija = "جاك طلب صفقة جديد",
            titleAr = "وصلك طلب صفقة جديد",
            titleFr = "Nouvelle proposition d'accord",
            bodyText = "$quantity $unit — $proposedPrice MAD",
            relatedId = dealId
        )
    }

    suspend fun counterDeal(deal: DealEntity, newPrice: Double, actorId: String, actorRole: String, isOnline: Boolean = true): Result<Unit> {
        if (!DealStateMachine.canTransition(deal.status, DealStatus.COUNTERED)) return Result.success(Unit)
        if (useRemoteBackend()) {
            return supabaseRepo.performDealAction(deal, "counter", newPrice = newPrice, note = "$newPrice MAD", actorId = actorId, actorRole = actorRole, isOnline = isOnline).map { }
        }
        val updated = deal.copy(
            proposedPrice = newPrice,
            status = DealStatus.COUNTERED,
            lastActorRole = actorRole,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsertDeal(updated)
        dao.insertDealHistory(
            DealStatusHistoryEntity(UUID.randomUUID().toString(), deal.id, DealStatus.COUNTERED, actorId, "$newPrice MAD", newPrice)
        )
        val recipient = if (actorRole == UserRole.FARMER) deal.buyerId else deal.farmerId
        createLocalNotification(recipient, "deal_countered", "اقتراح ثمن جديد فـ الصفقة", "عرض سعر مضاد في الصفقة", "Contre-offre reçue", "$newPrice MAD / ${deal.unit}", deal.id)
        return Result.success(Unit)
    }

    suspend fun acceptDeal(deal: DealEntity, actorId: String, actorRole: String, isOnline: Boolean = true): Result<Unit> {
        if (!DealStateMachine.canTransition(deal.status, DealStatus.ACCEPTED)) return Result.success(Unit)
        if (useRemoteBackend()) {
            return supabaseRepo.performDealAction(deal, "accept", note = "${deal.proposedPrice} MAD", actorId = actorId, actorRole = actorRole, isOnline = isOnline).map { }
        }
        val updated = deal.copy(
            agreedPrice = deal.proposedPrice,
            status = DealStatus.ACCEPTED,
            lastActorRole = actorRole,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsertDeal(updated)
        if (deal.offerId != null) updateOfferStatus(deal.offerId, OfferStatus.RESERVED, isOnline)
        dao.insertDealHistory(
            DealStatusHistoryEntity(UUID.randomUUID().toString(), deal.id, DealStatus.ACCEPTED, actorId, "${deal.proposedPrice} MAD", deal.proposedPrice)
        )
        logAnalytics("deal_accepted", actorId, """{"deal_id":"${deal.id}"}""")
        val recipient = if (actorRole == UserRole.FARMER) deal.buyerId else deal.farmerId
        createLocalNotification(recipient, "deal_accepted", "مبروك! تقبلات الصفقة", "تم قبول الصفقة بنجاح", "Accord accepté !", "${deal.proposedPrice} MAD / ${deal.unit}", deal.id)
        return Result.success(Unit)
    }

    suspend fun cancelDeal(deal: DealEntity, actorId: String, actorRole: String, isOnline: Boolean = true): Result<Unit> {
        if (!DealStateMachine.canTransition(deal.status, DealStatus.CANCELLED)) return Result.success(Unit)
        if (useRemoteBackend()) {
            return supabaseRepo.performDealAction(deal, "cancel", note = "Cancelled", actorId = actorId, actorRole = actorRole, isOnline = isOnline).map { }
        }
        val updated = deal.copy(status = DealStatus.CANCELLED, lastActorRole = actorRole, updatedAt = System.currentTimeMillis())
        dao.upsertDeal(updated)
        dao.insertDealHistory(
            DealStatusHistoryEntity(UUID.randomUUID().toString(), deal.id, DealStatus.CANCELLED, actorId, "Cancelled", deal.proposedPrice)
        )
        val recipient = if (actorRole == UserRole.FARMER) deal.buyerId else deal.farmerId
        createLocalNotification(recipient, "deal_cancelled", "تلغات الصفقة", "تم إلغاء الصفقة", "Accord annulé", "${deal.quantity} ${deal.unit}", deal.id)
        return Result.success(Unit)
    }

    suspend fun confirmDealCompletion(deal: DealEntity, actorId: String, actorRole: String, isOnline: Boolean = true): Result<Unit> {
        if (useRemoteBackend()) {
            return supabaseRepo.performDealAction(deal, "complete", note = "Completed (Cash on delivery)", actorId = actorId, actorRole = actorRole, isOnline = isOnline).map { }
        }
        val updated = DealStateMachine.applyConfirmation(deal, actorRole)
        dao.upsertDeal(updated)
        if (updated.status == DealStatus.COMPLETED) {
            if (deal.offerId != null) updateOfferStatus(deal.offerId, OfferStatus.SOLD, isOnline)
            dao.insertDealHistory(
                DealStatusHistoryEntity(UUID.randomUUID().toString(), deal.id, DealStatus.COMPLETED, actorId, "Completed (Cash on delivery)", updated.agreedPrice ?: updated.proposedPrice)
            )
            logAnalytics("deal_completed", actorId, """{"deal_id":"${deal.id}"}""")
            listOf(deal.farmerId, deal.buyerId).forEach { uid ->
                createLocalNotification(uid, "deal_completed", "كملات الصفقة بنجاح", "اكتملت الصفقة بنجاح", "Transaction terminée avec succès", "${updated.agreedPrice ?: updated.proposedPrice} MAD", deal.id)
            }
        }
        return Result.success(Unit)
    }

    suspend fun submitRating(dealId: String, fromUserId: String, toUserId: String, stars: Int, comment: String, isOnline: Boolean = true) {
        val rating = RatingEntity(
            id = UUID.randomUUID().toString(),
            dealId = dealId,
            fromUserId = fromUserId,
            toUserId = toUserId,
            stars = stars.coerceIn(1, 5),
            comment = comment.trim()
        )
        if (useRemoteBackend()) {
            supabaseRepo.submitRating(rating, isOnline)
            return
        }
        dao.insertRating(rating)
        val allToUser = dao.observeAllRatings().first().filter { it.toUserId == toUserId }
        val profile = dao.getProfileById(toUserId) ?: return
        val avg = if (allToUser.isEmpty()) stars.toDouble() else allToUser.map { it.stars }.average()
        dao.upsertProfile(profile.copy(ratingAvg = avg, ratingCount = allToUser.size))
    }

    suspend fun saveFilter(buyerId: String, label: String, categoryId: String?, productId: String?, city: String?, maxPrice: Double?, minQty: Double?, isOnline: Boolean = true) {
        val filter = SavedFilterEntity(
            id = UUID.randomUUID().toString(),
            buyerId = buyerId,
            label = label,
            categoryId = categoryId,
            productId = productId,
            city = city,
            maxPrice = maxPrice,
            minQty = minQty
        )
        if (useRemoteBackend()) {
            supabaseRepo.upsertSavedFilter(filter, isOnline)
            return
        }
        dao.upsertSavedFilter(filter)
    }

    suspend fun deleteSavedFilter(filterId: String, isOnline: Boolean = true) {
        if (useRemoteBackend()) {
            supabaseRepo.deleteSavedFilter(filterId, isOnline)
            return
        }
        dao.deleteSavedFilter(filterId)
    }

    suspend fun submitProductProposal(farmerId: String, name: String, categoryId: String, unit: String, notes: String, isOnline: Boolean = true) {
        val prop = ProductProposalEntity(
            id = UUID.randomUUID().toString(),
            farmerId = farmerId,
            proposedName = name.trim(),
            categoryId = categoryId,
            suggestedUnit = unit,
            notes = notes.trim()
        )
        if (useRemoteBackend()) {
            supabaseRepo.submitProposal(prop, isOnline)
            return
        }
        dao.upsertProductProposal(prop)
    }

    suspend fun submitReport(reporterId: String, targetType: String, targetId: String, reason: String, isOnline: Boolean = true) {
        val rep = ReportEntity(
            id = UUID.randomUUID().toString(),
            reporterId = reporterId,
            targetType = targetType,
            targetId = targetId,
            reason = reason.trim()
        )
        if (useRemoteBackend()) {
            supabaseRepo.submitReport(rep, isOnline)
            return
        }
        dao.upsertReport(rep)
    }

    suspend fun markAllNotificationsRead(userId: String, isOnline: Boolean = true) {
        if (useRemoteBackend()) {
            supabaseRepo.markNotificationsRead(userId, isOnline)
            return
        }
        dao.markAllNotificationsRead(userId)
    }

    suspend fun fetchRemoteMarketSummary(productId: String, regionId: String?, preferredUnit: String?): ProductMarketSummary? {
        if (!useRemoteBackend()) return null
        return supabaseRepo.fetchMarketPriceSummary(productId, regionId, preferredUnit)
    }

    // Admin actions
    suspend fun setVerificationStatus(userId: String, status: String, reason: String = ""): Result<Unit> {
        if (useRemoteBackend()) {
            return supabaseRepo.adminSetVerificationStatus(userId, status, reason)
        }
        val profile = dao.getProfileById(userId) ?: return Result.success(Unit)
        dao.upsertProfile(profile.copy(verificationStatus = status, rejectionReason = reason.trim()))
        createLocalNotification(
            userId = userId,
            type = "verification",
            titleDarija = if (status == VerificationStatus.VERIFIED) "مبروك! تأكد الحساب ديالك" else "تحديث حالة الحساب",
            titleAr = if (status == VerificationStatus.VERIFIED) "تم توثيق حسابك" else "تحديث حالة التوثيق",
            titleFr = if (status == VerificationStatus.VERIFIED) "Compte vérifié !" else "Statut de vérification mis à jour",
            bodyText = reason.ifBlank { status },
            relatedId = userId
        )
        return Result.success(Unit)
    }

    suspend fun addProductToCatalog(
        categoryId: String,
        nameDarija: String,
        nameAr: String,
        nameFr: String,
        allowedUnitsCsv: String,
        attributeSchemaJson: String
    ): Result<Unit> {
        val prod = ProductEntity(
            id = "prod_${UUID.randomUUID().toString().take(8)}",
            categoryId = categoryId,
            nameDarija = nameDarija.trim(),
            nameAr = nameAr.trim().ifBlank { nameDarija.trim() },
            nameFr = nameFr.trim().ifBlank { nameDarija.trim() },
            allowedUnitsCsv = allowedUnitsCsv,
            attributeSchemaJson = attributeSchemaJson,
            imageKey = "hero"
        )
        if (useRemoteBackend()) {
            return supabaseRepo.adminAddProduct(prod)
        }
        dao.upsertProduct(prod)
        return Result.success(Unit)
    }

    suspend fun addReferencePrice(productId: String, regionId: String, unit: String, priceMad: Double): Result<Unit> {
        val ref = ReferencePriceEntity(
            id = UUID.randomUUID().toString(),
            productId = productId,
            regionId = regionId,
            unit = unit,
            priceMad = priceMad,
            recordedEpochDay = LocalDate.now().toEpochDay(),
            source = "admin"
        )
        if (useRemoteBackend()) {
            return supabaseRepo.adminAddReferencePrice(ref)
        }
        dao.upsertReferencePrice(ref)
        return Result.success(Unit)
    }

    suspend fun approveProposal(proposal: ProductProposalEntity): Result<Unit> {
        if (useRemoteBackend()) {
            val res = supabaseRepo.adminSetProposalStatus(proposal, "approved")
            if (res.isFailure) return res
        } else {
            dao.upsertProductProposal(proposal.copy(status = "approved"))
        }
        return addProductToCatalog(
            categoryId = proposal.categoryId,
            nameDarija = proposal.proposedName,
            nameAr = proposal.proposedName,
            nameFr = proposal.proposedName,
            allowedUnitsCsv = proposal.suggestedUnit,
            attributeSchemaJson = """[{"key":"grade","labelDarija":"الجودة","labelAr":"الجودة","labelFr":"Qualité","type":"select","required":true,"options":["A","B","C"]}]"""
        )
    }

    suspend fun rejectProposal(proposal: ProductProposalEntity): Result<Unit> {
        if (useRemoteBackend()) {
            return supabaseRepo.adminSetProposalStatus(proposal, "rejected")
        }
        dao.upsertProductProposal(proposal.copy(status = "rejected"))
        return Result.success(Unit)
    }

    suspend fun resolveReport(report: ReportEntity): Result<Unit> {
        if (useRemoteBackend()) {
            return supabaseRepo.adminResolveReport(report)
        }
        dao.upsertReport(report.copy(status = "resolved"))
        return Result.success(Unit)
    }

    suspend fun syncPendingItemsNow(): SyncRunSummary {
        if (useRemoteBackend()) {
            return supabaseRepo.syncPendingItemsNow()
        }
        val pending = dao.getPendingSyncQueue()
        var syncedCount = 0
        for (item in pending) {
            when (item.entityType) {
                "offer" -> dao.updateOfferSyncState(item.entityId, SyncState.SYNCED)
                "buy_request" -> dao.updateBuyRequestSyncState(item.entityId, SyncState.SYNCED)
                "deal" -> dao.updateDealSyncState(item.entityId, SyncState.SYNCED)
            }
            dao.removeSyncItem(item.id)
            syncedCount++
        }
        return SyncRunSummary(syncedCount, 0, 0)
    }

    suspend fun retryFailedSyncItems(): SyncRunSummary {
        if (useRemoteBackend()) {
            return supabaseRepo.retryFailedSyncItems()
        }
        dao.resetFailedSyncItems()
        return syncPendingItemsNow()
    }

    private suspend fun createLocalNotification(
        userId: String,
        type: String,
        titleDarija: String,
        titleAr: String,
        titleFr: String,
        bodyText: String,
        relatedId: String
    ) {
        dao.insertNotification(
            NotificationEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                type = type,
                titleDarija = titleDarija,
                titleAr = titleAr,
                titleFr = titleFr,
                bodyText = bodyText,
                relatedEntityId = relatedId
            )
        )
        notificationPort.dispatch(userId, titleDarija, bodyText, type)
    }

    private suspend fun logAnalytics(name: String, userId: String, metaJson: String) {
        dao.insertAnalyticsEvent(
            AnalyticsEventEntity(
                id = UUID.randomUUID().toString(),
                eventName = name,
                userId = userId,
                metadataJson = metaJson
            )
        )
    }
}
