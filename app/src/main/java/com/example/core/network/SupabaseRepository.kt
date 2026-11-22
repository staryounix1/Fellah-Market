package com.example.core.network

import com.example.core.auth.AuthErrorCode
import com.example.core.auth.AuthException
import com.example.core.auth.SessionManager
import com.example.core.auth.SupabaseAuthClient
import com.example.core.data.*
import com.example.core.domain.PriceAnalytics
import com.example.core.domain.ProductMarketSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

data class OfferQueryFilter(
    val categoryId: String? = null,
    val productId: String? = null,
    val city: String? = null,
    val maxPrice: Double? = null,
    val minQty: Double? = null,
    val sortOrder: String = "created_at.desc",
    val page: Int = 0,
    val pageSize: Int = 20
)

data class PaginatedResult<T>(
    val items: List<T>,
    val hasMore: Boolean,
    val totalCount: Int?
)

data class SyncRunSummary(
    val syncedCount: Int,
    val failedCount: Int,
    val pendingCount: Int
)

private sealed class HttpCallResult {
    data class Success(val code: Int, val body: String, val contentRange: String?) : HttpCallResult()
    data class Failure(
        val code: Int,
        val message: String,
        val isTransient: Boolean,
        val isSessionExpired: Boolean = false
    ) : HttpCallResult()
}

class SupabaseRepository(
    private val dao: FellahDao,
    private val authClient: SupabaseAuthClient,
    private val sessionManager: SessionManager,
    private val baseUrlProvider: () -> String,
    private val anonKeyProvider: () -> String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val onSessionExpired: () -> Unit = {},
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun baseUrl(): String = baseUrlProvider().trim().trimEnd('/')
    private fun anonKey(): String = anonKeyProvider().trim()
    fun isRemoteConfigured(): Boolean = baseUrl().isNotEmpty() && anonKey().isNotEmpty()

    suspend fun fetchProfileById(userId: String): Result<ProfileEntity?> = withContext(Dispatchers.IO) {
        val url = "${baseUrl()}/rest/v1/profiles?id=eq.$userId&select=*&limit=1"
        when (val res = executeAuthorizedGet(url)) {
            is HttpCallResult.Success -> {
                val profile = PostgrestCodec.parseList(res.body).firstOrNull()?.let { PostgrestCodec.toProfile(it) }
                if (profile != null) mergeProfilesLww(listOf(profile))
                Result.success(profile)
            }
            is HttpCallResult.Failure -> Result.failure(IllegalStateException(res.message))
        }
    }

    suspend fun upsertProfileRemote(
        profile: ProfileEntity,
        farmOrCoopName: String,
        businessName: String,
        businessType: String,
        categoryIds: List<String>,
        isOnline: Boolean
    ): Result<ProfileEntity> = withContext(Dispatchers.IO) {
        val updated = profile.copy(updatedAt = clockMillis())
        dao.upsertProfile(updated)
        if (updated.role == UserRole.FARMER) {
            dao.upsertFarmerDetails(FarmerDetailsEntity(updated.id, farmOrCoopName, categoryIds.joinToString(",")))
        } else if (updated.role == UserRole.BUYER) {
            dao.upsertBuyerDetails(BuyerDetailsEntity(updated.id, businessName, businessType, categoryIds.joinToString(",")))
        }

        val profileMap = mapOf(
            "id" to updated.id,
            "phone" to updated.phone,
            "full_name" to updated.fullName,
            "role" to updated.role,
            "region_id" to updated.regionId,
            "city" to updated.city,
            "verification_doc_path" to updated.verificationDocPath,
            "avatar_url" to updated.avatarUrl,
            "updated_at" to PostgrestCodec.formatTimestampIso(updated.updatedAt)
        )

        if (!isOnline) {
            enqueueSyncItem("profile", updated.id, "upsert", PostgrestCodec.mapAdapter.toJson(profileMap))
            return@withContext Result.success(updated)
        }

        val profileRes = executeAuthorizedWrite(
            method = "POST",
            url = "${baseUrl()}/rest/v1/profiles?on_conflict=id",
            jsonBody = PostgrestCodec.mapAdapter.toJson(profileMap),
            prefer = "resolution=merge-duplicates,return=representation"
        )
        if (profileRes is HttpCallResult.Failure) {
            if (profileRes.isTransient) {
                enqueueSyncItem("profile", updated.id, "upsert", PostgrestCodec.mapAdapter.toJson(profileMap))
                return@withContext Result.success(updated)
            }
            return@withContext Result.failure(IllegalStateException(profileRes.message))
        }

        if (updated.role == UserRole.FARMER) {
            val fdJson = PostgrestCodec.mapAdapter.toJson(
                mapOf(
                    "user_id" to updated.id,
                    "farm_or_coop_name" to farmOrCoopName,
                    "main_category_ids" to categoryIds
                )
            )
            executeAuthorizedWrite(
                method = "POST",
                url = "${baseUrl()}/rest/v1/farmer_details?on_conflict=user_id",
                jsonBody = fdJson,
                prefer = "resolution=merge-duplicates"
            )
        } else if (updated.role == UserRole.BUYER) {
            val bdJson = PostgrestCodec.mapAdapter.toJson(
                mapOf(
                    "user_id" to updated.id,
                    "business_name" to businessName.ifBlank { updated.fullName },
                    "business_type" to businessType,
                    "interest_category_ids" to categoryIds
                )
            )
            executeAuthorizedWrite(
                method = "POST",
                url = "${baseUrl()}/rest/v1/buyer_details?on_conflict=user_id",
                jsonBody = bdJson,
                prefer = "resolution=merge-duplicates"
            )
        }

        val serverProfile = (profileRes as HttpCallResult.Success).let {
            PostgrestCodec.parseList(it.body).firstOrNull()?.let { row -> PostgrestCodec.toProfile(row) }
        } ?: updated
        dao.upsertProfile(serverProfile)
        Result.success(serverProfile)
    }

    suspend fun refreshCatalog(): Result<Unit> = withContext(Dispatchers.IO) {
        val regRes = executeAuthorizedGet("${baseUrl()}/rest/v1/regions?is_active=eq.true&select=*")
        if (regRes is HttpCallResult.Success) {
            val regions = PostgrestCodec.parseList(regRes.body).mapNotNull { PostgrestCodec.toRegion(it) }
            if (regions.isNotEmpty()) dao.upsertRegions(regions)
        } else if (regRes is HttpCallResult.Failure) {
            return@withContext Result.failure(IllegalStateException(regRes.message))
        }

        val catRes = executeAuthorizedGet("${baseUrl()}/rest/v1/categories?is_active=eq.true&order=sort_order.asc&select=*")
        if (catRes is HttpCallResult.Success) {
            val cats = PostgrestCodec.parseList(catRes.body).mapNotNull { PostgrestCodec.toCategory(it) }
            if (cats.isNotEmpty()) dao.upsertCategories(cats)
        }

        val schemasRes = executeAuthorizedGet("${baseUrl()}/rest/v1/product_attribute_schemas?order=version.asc&select=product_id,schema_json,version")
        val schemaByProduct = mutableMapOf<String, String>()
        if (schemasRes is HttpCallResult.Success) {
            PostgrestCodec.parseList(schemasRes.body).forEach { row ->
                val pid = row["product_id"] as? String ?: return@forEach
                val rawSchema = row["schema_json"] ?: return@forEach
                schemaByProduct[pid] = when (rawSchema) {
                    is String -> rawSchema
                    else -> PostgrestCodec.moshi.adapter(Any::class.java).toJson(rawSchema)
                }
            }
        }

        val prodRes = executeAuthorizedGet("${baseUrl()}/rest/v1/products?is_active=eq.true&select=*")
        if (prodRes is HttpCallResult.Success) {
            val prods = PostgrestCodec.parseList(prodRes.body).mapNotNull { PostgrestCodec.toProduct(it, schemaByProduct) }
            if (prods.isNotEmpty()) dao.upsertProducts(prods)
        }

        val refRes = executeAuthorizedGet("${baseUrl()}/rest/v1/reference_prices?order=recorded_date.asc&select=*")
        if (refRes is HttpCallResult.Success) {
            val refs = PostgrestCodec.parseList(refRes.body).mapNotNull { PostgrestCodec.toReferencePrice(it) }
            if (refs.isNotEmpty()) dao.upsertReferencePrices(refs)
        }

        Result.success(Unit)
    }

    suspend fun fetchOffersPage(filter: OfferQueryFilter): Result<PaginatedResult<OfferEntity>> = withContext(Dispatchers.IO) {
        val urlBuilder = "${baseUrl()}/rest/v1/offers".toHttpUrlOrNull()?.newBuilder()
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid base URL"))
        urlBuilder.addQueryParameter("select", "*,offer_photos(id,storage_path,sort_order)")
        urlBuilder.addQueryParameter("status", "in.(active,reserved)")
        filter.categoryId?.let { urlBuilder.addQueryParameter("category_id", "eq.$it") }
        filter.productId?.let { urlBuilder.addQueryParameter("product_id", "eq.$it") }
        filter.city?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("city", "eq.$it") }
        filter.maxPrice?.let { urlBuilder.addQueryParameter("asking_price", "lte.$it") }
        filter.minQty?.let { urlBuilder.addQueryParameter("quantity", "gte.$it") }
        urlBuilder.addQueryParameter("order", filter.sortOrder)

        val offset = filter.page.coerceAtLeast(0) * filter.pageSize.coerceAtLeast(1)
        val end = offset + filter.pageSize.coerceAtLeast(1) - 1

        when (val res = executeAuthorizedGet(urlBuilder.build().toString(), rangeHeader = "$offset-$end", preferCount = true)) {
            is HttpCallResult.Success -> {
                val items = PostgrestCodec.parseList(res.body).mapNotNull { PostgrestCodec.toOffer(it) }
                mergeOffersLww(items)
                val (parsedEnd, total) = parseContentRange(res.contentRange)
                val hasMore = when {
                    total != null && parsedEnd != null -> (parsedEnd + 1) < total
                    else -> items.size >= filter.pageSize
                }
                Result.success(PaginatedResult(items = items, hasMore = hasMore, totalCount = total))
            }
            is HttpCallResult.Failure -> Result.failure(IllegalStateException(res.message))
        }
    }

    suspend fun refreshAllUserData(currentUserId: String, isAdmin: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        refreshCatalog()
        fetchOffersPage(OfferQueryFilter(page = 0, pageSize = 25))

        // Fetch public_profiles (masks phone unless accepted/completed deal or own/admin)
        val pubProfRes = executeAuthorizedGet("${baseUrl()}/rest/v1/public_profiles?select=*")
        if (pubProfRes is HttpCallResult.Success) {
            val list = PostgrestCodec.parseList(pubProfRes.body).mapNotNull { PostgrestCodec.toProfile(it) }
            mergeProfilesLww(list)
        }
        if (isAdmin) {
            val fullProfRes = executeAuthorizedGet("${baseUrl()}/rest/v1/profiles?select=*")
            if (fullProfRes is HttpCallResult.Success) {
                val list = PostgrestCodec.parseList(fullProfRes.body).mapNotNull { PostgrestCodec.toProfile(it) }
                mergeProfilesLww(list)
            }
        } else {
            fetchProfileById(currentUserId)
        }

        // Farmer & Buyer details
        (executeAuthorizedGet("${baseUrl()}/rest/v1/farmer_details?select=*") as? HttpCallResult.Success)?.let {
            dao.upsertFarmerDetailsList(PostgrestCodec.parseList(it.body).mapNotNull { r -> PostgrestCodec.toFarmerDetails(r) })
        }
        (executeAuthorizedGet("${baseUrl()}/rest/v1/buyer_details?select=*") as? HttpCallResult.Success)?.let {
            dao.upsertBuyerDetailsList(PostgrestCodec.parseList(it.body).mapNotNull { r -> PostgrestCodec.toBuyerDetails(r) })
        }

        // My offers (all statuses including paused/sold)
        (executeAuthorizedGet("${baseUrl()}/rest/v1/offers?farmer_id=eq.$currentUserId&select=*,offer_photos(id,storage_path,sort_order)&order=created_at.desc") as? HttpCallResult.Success)?.let {
            mergeOffersLww(PostgrestCodec.parseList(it.body).mapNotNull { r -> PostgrestCodec.toOffer(r) })
        }

        // Buy requests
        (executeAuthorizedGet("${baseUrl()}/rest/v1/buy_requests?order=created_at.desc&select=*") as? HttpCallResult.Success)?.let {
            mergeBuyRequestsLww(PostgrestCodec.parseList(it.body).mapNotNull { r -> PostgrestCodec.toBuyRequest(r) })
        }

        // Deals & history & ratings
        (executeAuthorizedGet("${baseUrl()}/rest/v1/deals?order=updated_at.desc&select=*") as? HttpCallResult.Success)?.let {
            mergeDealsLww(PostgrestCodec.parseList(it.body).mapNotNull { r -> PostgrestCodec.toDeal(r) })
        }
        (executeAuthorizedGet("${baseUrl()}/rest/v1/deal_status_history?order=created_at.asc&select=*") as? HttpCallResult.Success)?.let {
            dao.upsertDealHistoryList(PostgrestCodec.parseList(it.body).mapNotNull { r -> PostgrestCodec.toDealHistory(r) })
        }
        (executeAuthorizedGet("${baseUrl()}/rest/v1/ratings?order=created_at.desc&select=*") as? HttpCallResult.Success)?.let {
            dao.upsertRatings(PostgrestCodec.parseList(it.body).mapNotNull { r -> PostgrestCodec.toRating(r) })
        }

        // Notifications & saved filters & proposals & reports
        (executeAuthorizedGet("${baseUrl()}/rest/v1/notifications?user_id=eq.$currentUserId&order=created_at.desc&select=*") as? HttpCallResult.Success)?.let {
            dao.upsertNotifications(PostgrestCodec.parseList(it.body).mapNotNull { r -> PostgrestCodec.toNotification(r) })
        }
        (executeAuthorizedGet("${baseUrl()}/rest/v1/saved_filters?buyer_id=eq.$currentUserId&order=created_at.desc&select=*") as? HttpCallResult.Success)?.let {
            dao.upsertSavedFilters(PostgrestCodec.parseList(it.body).mapNotNull { r -> PostgrestCodec.toSavedFilter(r) })
        }
        (executeAuthorizedGet("${baseUrl()}/rest/v1/product_proposals?order=created_at.desc&select=*") as? HttpCallResult.Success)?.let {
            dao.upsertProductProposals(PostgrestCodec.parseList(it.body).mapNotNull { r -> PostgrestCodec.toProductProposal(r) })
        }
        (executeAuthorizedGet("${baseUrl()}/rest/v1/reports?order=created_at.desc&select=*") as? HttpCallResult.Success)?.let {
            dao.upsertReports(PostgrestCodec.parseList(it.body).mapNotNull { r -> PostgrestCodec.toReport(r) })
        }

        Result.success(Unit)
    }

    suspend fun upsertOffer(offer: OfferEntity, isOnline: Boolean): Result<OfferEntity> = withContext(Dispatchers.IO) {
        val normalizedId = ensureUuid(offer.id)
        val toSave = offer.copy(
            id = normalizedId,
            syncState = if (isOnline) SyncState.SYNCED else SyncState.PENDING_SYNC,
            updatedAt = clockMillis()
        )
        dao.upsertOffer(toSave)
        val jsonBody = PostgrestCodec.mapAdapter.toJson(PostgrestCodec.offerToRemoteMap(toSave))
        if (!isOnline) {
            enqueueSyncItem("offer", toSave.id, "upsert", jsonBody)
            return@withContext Result.success(toSave.copy(syncState = SyncState.PENDING_SYNC))
        }

        when (val res = executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/offers?on_conflict=id", jsonBody, "resolution=merge-duplicates,return=representation")) {
            is HttpCallResult.Success -> {
                val remote = PostgrestCodec.parseList(res.body).firstOrNull()?.let { PostgrestCodec.toOffer(it) }
                // Preserve local photo/voice paths while media is still uploading
                val merged = if (remote != null) {
                    remote.copy(
                        photoUrisCsv = if (toSave.photoUrisCsv.isNotBlank() && remote.photoUrisCsv.isBlank()) toSave.photoUrisCsv else remote.photoUrisCsv,
                        voiceNotePath = if (toSave.voiceNotePath.startsWith("/") && remote.voiceNotePath.isBlank()) toSave.voiceNotePath else remote.voiceNotePath,
                        syncState = SyncState.SYNCED
                    )
                } else toSave.copy(syncState = SyncState.SYNCED)
                dao.upsertOffer(merged)
                Result.success(merged)
            }
            is HttpCallResult.Failure -> {
                dao.upsertOffer(toSave.copy(syncState = SyncState.PENDING_SYNC))
                enqueueSyncItem("offer", toSave.id, "upsert", jsonBody)
                if (res.isTransient) Result.success(toSave.copy(syncState = SyncState.PENDING_SYNC))
                else Result.failure(IllegalStateException(res.message))
            }
        }
    }

    suspend fun updateOfferStatus(offerId: String, status: String, isOnline: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val current = dao.getOfferById(offerId) ?: return@withContext Result.success(Unit)
        val updated = current.copy(
            status = status,
            updatedAt = clockMillis(),
            syncState = if (isOnline) SyncState.SYNCED else SyncState.PENDING_SYNC
        )
        dao.upsertOffer(updated)
        val patchJson = PostgrestCodec.mapAdapter.toJson(
            mapOf("status" to status, "updated_at" to PostgrestCodec.formatTimestampIso(updated.updatedAt))
        )
        if (!isOnline) {
            enqueueSyncItem("offer", offerId, "update_status", patchJson)
            return@withContext Result.success(Unit)
        }
        when (val res = executeAuthorizedWrite("PATCH", "${baseUrl()}/rest/v1/offers?id=eq.$offerId", patchJson)) {
            is HttpCallResult.Success -> Result.success(Unit)
            is HttpCallResult.Failure -> {
                dao.updateOfferSyncState(offerId, SyncState.PENDING_SYNC)
                enqueueSyncItem("offer", offerId, "update_status", patchJson)
                if (res.isTransient) Result.success(Unit) else Result.failure(IllegalStateException(res.message))
            }
        }
    }

    suspend fun upsertBuyRequest(request: BuyRequestEntity, isOnline: Boolean): Result<BuyRequestEntity> = withContext(Dispatchers.IO) {
        val toSave = request.copy(
            id = ensureUuid(request.id),
            syncState = if (isOnline) SyncState.SYNCED else SyncState.PENDING_SYNC,
            updatedAt = clockMillis()
        )
        dao.upsertBuyRequest(toSave)
        val jsonBody = PostgrestCodec.mapAdapter.toJson(PostgrestCodec.buyRequestToRemoteMap(toSave))
        if (!isOnline) {
            enqueueSyncItem("buy_request", toSave.id, "upsert", jsonBody)
            return@withContext Result.success(toSave.copy(syncState = SyncState.PENDING_SYNC))
        }
        when (val res = executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/buy_requests?on_conflict=id", jsonBody, "resolution=merge-duplicates,return=representation")) {
            is HttpCallResult.Success -> {
                val remote = PostgrestCodec.parseList(res.body).firstOrNull()?.let { PostgrestCodec.toBuyRequest(it) } ?: toSave.copy(syncState = SyncState.SYNCED)
                dao.upsertBuyRequest(remote)
                Result.success(remote)
            }
            is HttpCallResult.Failure -> {
                dao.updateBuyRequestSyncState(toSave.id, SyncState.PENDING_SYNC)
                enqueueSyncItem("buy_request", toSave.id, "upsert", jsonBody)
                if (res.isTransient) Result.success(toSave) else Result.failure(IllegalStateException(res.message))
            }
        }
    }

    suspend fun createDeal(deal: DealEntity, actorId: String, isOnline: Boolean): Result<DealEntity> = withContext(Dispatchers.IO) {
        val toSave = deal.copy(
            id = ensureUuid(deal.id),
            syncState = if (isOnline) SyncState.SYNCED else SyncState.PENDING_SYNC,
            updatedAt = clockMillis()
        )
        dao.upsertDeal(toSave)
        dao.insertDealHistory(
            DealStatusHistoryEntity(
                id = UUID.randomUUID().toString(),
                dealId = toSave.id,
                status = DealStatus.REQUESTED,
                actorId = actorId,
                note = toSave.note.ifBlank { "${toSave.quantity} ${toSave.unit} @ ${toSave.proposedPrice} MAD" },
                priceAtStep = toSave.proposedPrice,
                createdAt = toSave.createdAt
            )
        )
        val jsonBody = PostgrestCodec.mapAdapter.toJson(PostgrestCodec.dealToRemoteMap(toSave))
        if (!isOnline) {
            enqueueSyncItem("deal", toSave.id, "upsert", jsonBody)
            return@withContext Result.success(toSave.copy(syncState = SyncState.PENDING_SYNC))
        }
        when (val res = executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/deals?on_conflict=id", jsonBody, "resolution=merge-duplicates,return=representation")) {
            is HttpCallResult.Success -> {
                val remote = PostgrestCodec.parseList(res.body).firstOrNull()?.let { PostgrestCodec.toDeal(it) } ?: toSave.copy(syncState = SyncState.SYNCED)
                dao.upsertDeal(remote)
                val histJson = PostgrestCodec.mapAdapter.toJson(
                    mapOf(
                        "deal_id" to remote.id,
                        "status" to DealStatus.REQUESTED,
                        "actor_id" to actorId,
                        "note" to remote.note.ifBlank { "${remote.quantity} ${remote.unit} @ ${remote.proposedPrice} MAD" },
                        "price_at_step" to remote.proposedPrice
                    )
                )
                executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/deal_status_history", histJson)
                Result.success(remote)
            }
            is HttpCallResult.Failure -> {
                dao.updateDealSyncState(toSave.id, SyncState.PENDING_SYNC)
                enqueueSyncItem("deal", toSave.id, "upsert", jsonBody)
                if (res.isTransient) Result.success(toSave) else Result.failure(IllegalStateException(res.message))
            }
        }
    }

    suspend fun performDealAction(
        deal: DealEntity,
        action: String,
        newPrice: Double? = null,
        note: String = "",
        actorId: String,
        actorRole: String,
        isOnline: Boolean
    ): Result<DealEntity> = withContext(Dispatchers.IO) {
        val rpcMap = mapOf(
            "p_deal_id" to deal.id,
            "p_action" to action,
            "p_new_price" to newPrice,
            "p_note" to note
        )
        val rpcJson = PostgrestCodec.mapAdapter.toJson(rpcMap)

        if (!isOnline) {
            val optimistic = applyOptimisticDealUpdate(deal, action, newPrice, actorRole)
            dao.upsertDeal(optimistic.copy(syncState = SyncState.PENDING_SYNC))
            enqueueSyncItem("deal_action", deal.id, "rpc", rpcJson)
            return@withContext Result.success(optimistic)
        }

        when (val res = executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/rpc/deal_action", rpcJson)) {
            is HttpCallResult.Success -> {
                val parsed = PostgrestCodec.parseObject(res.body)?.let { PostgrestCodec.toDeal(it) }
                    ?: PostgrestCodec.parseList(res.body).firstOrNull()?.let { PostgrestCodec.toDeal(it) }
                    ?: applyOptimisticDealUpdate(deal, action, newPrice, actorRole)
                dao.upsertDeal(parsed.copy(syncState = SyncState.SYNCED))
                // Refresh deal history & public_profiles (for phone unmasking on accept)
                (executeAuthorizedGet("${baseUrl()}/rest/v1/deal_status_history?deal_id=eq.${deal.id}&order=created_at.asc&select=*") as? HttpCallResult.Success)?.let {
                    dao.upsertDealHistoryList(PostgrestCodec.parseList(it.body).mapNotNull { r -> PostgrestCodec.toDealHistory(r) })
                }
                if (parsed.status == DealStatus.ACCEPTED) {
                    val partnerId = if (actorId == parsed.farmerId) parsed.buyerId else parsed.farmerId
                    (executeAuthorizedGet("${baseUrl()}/rest/v1/public_profiles?id=eq.$partnerId&select=*") as? HttpCallResult.Success)?.let {
                        mergeProfilesLww(PostgrestCodec.parseList(it.body).mapNotNull { r -> PostgrestCodec.toProfile(r) })
                    }
                }
                Result.success(parsed)
            }
            is HttpCallResult.Failure -> {
                if (res.isTransient) {
                    val optimistic = applyOptimisticDealUpdate(deal, action, newPrice, actorRole).copy(syncState = SyncState.PENDING_SYNC)
                    dao.upsertDeal(optimistic)
                    enqueueSyncItem("deal_action", deal.id, "rpc", rpcJson)
                    Result.success(optimistic)
                } else {
                    Result.failure(IllegalStateException(res.message))
                }
            }
        }
    }

    suspend fun fetchMarketPriceSummary(productId: String, regionId: String?, unit: String?): ProductMarketSummary =
        withContext(Dispatchers.IO) {
            if (isRemoteConfigured() && sessionManager.getSession() != null) {
                val rpcBody = PostgrestCodec.mapAdapter.toJson(
                    mapOf("p_product_id" to productId, "p_region_id" to regionId, "p_unit" to unit)
                )
                val res = executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/rpc/get_market_price_summary", rpcBody)
                if (res is HttpCallResult.Success) {
                    PostgrestCodec.parseObject(res.body)?.let { obj ->
                        return@withContext PostgrestCodec.toMarketSummary(obj, productId)
                    }
                }
            }
            // Offline / fallback from Room cache
            PriceAnalytics.computeMarketSummary(
                productId = productId,
                regionId = regionId,
                deals = emptyList(),
                offers = emptyList(),
                referencePrices = emptyList(),
                todayEpochDay = LocalDate.now().toEpochDay(),
                preferredUnit = unit
            )
        }

    suspend fun submitRating(rating: RatingEntity, isOnline: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val toSave = rating.copy(id = ensureUuid(rating.id))
        dao.insertRating(toSave)
        val payload = PostgrestCodec.mapAdapter.toJson(
            mapOf(
                "id" to toSave.id,
                "deal_id" to toSave.dealId,
                "from_user_id" to toSave.fromUserId,
                "to_user_id" to toSave.toUserId,
                "stars" to toSave.stars,
                "comment" to toSave.comment
            )
        )
        if (!isOnline) {
            enqueueSyncItem("rating", toSave.id, "upsert", payload)
            return@withContext Result.success(Unit)
        }
        when (val res = executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/ratings", payload)) {
            is HttpCallResult.Success -> {
                fetchProfileById(toSave.toUserId)
                Result.success(Unit)
            }
            is HttpCallResult.Failure -> {
                if (res.isTransient) {
                    enqueueSyncItem("rating", toSave.id, "upsert", payload)
                    Result.success(Unit)
                } else Result.failure(IllegalStateException(res.message))
            }
        }
    }

    suspend fun upsertSavedFilter(filter: SavedFilterEntity, isOnline: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val toSave = filter.copy(id = ensureUuid(filter.id))
        dao.upsertSavedFilter(toSave)
        val payload = PostgrestCodec.mapAdapter.toJson(
            mapOf(
                "id" to toSave.id,
                "buyer_id" to toSave.buyerId,
                "label" to toSave.label,
                "category_id" to toSave.categoryId,
                "product_id" to toSave.productId,
                "city" to toSave.city,
                "max_price" to toSave.maxPrice,
                "min_qty" to toSave.minQty
            )
        )
        if (!isOnline) {
            enqueueSyncItem("saved_filter", toSave.id, "upsert", payload)
            return@withContext Result.success(Unit)
        }
        executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/saved_filters", payload)
        Result.success(Unit)
    }

    suspend fun deleteSavedFilter(filterId: String, isOnline: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        dao.deleteSavedFilter(filterId)
        if (!isOnline) {
            enqueueSyncItem("saved_filter", filterId, "delete", "{}")
            return@withContext Result.success(Unit)
        }
        executeAuthorizedWrite("DELETE", "${baseUrl()}/rest/v1/saved_filters?id=eq.$filterId", null)
        Result.success(Unit)
    }

    suspend fun submitProposal(proposal: ProductProposalEntity, isOnline: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val toSave = proposal.copy(id = ensureUuid(proposal.id))
        dao.upsertProductProposal(toSave)
        val payload = PostgrestCodec.mapAdapter.toJson(
            mapOf(
                "id" to toSave.id,
                "farmer_id" to toSave.farmerId,
                "proposed_name" to toSave.proposedName,
                "category_id" to toSave.categoryId,
                "suggested_unit" to toSave.suggestedUnit,
                "notes" to toSave.notes,
                "status" to toSave.status
            )
        )
        if (!isOnline) {
            enqueueSyncItem("product_proposal", toSave.id, "upsert", payload)
            return@withContext Result.success(Unit)
        }
        executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/product_proposals", payload)
        Result.success(Unit)
    }

    suspend fun submitReport(report: ReportEntity, isOnline: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val toSave = report.copy(id = ensureUuid(report.id))
        dao.upsertReport(toSave)
        val payload = PostgrestCodec.mapAdapter.toJson(
            mapOf(
                "id" to toSave.id,
                "reporter_id" to toSave.reporterId,
                "target_type" to toSave.targetType,
                "target_id" to toSave.targetId,
                "reason" to toSave.reason,
                "status" to toSave.status
            )
        )
        if (!isOnline) {
            enqueueSyncItem("report", toSave.id, "upsert", payload)
            return@withContext Result.success(Unit)
        }
        executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/reports", payload)
        Result.success(Unit)
    }

    suspend fun markNotificationsRead(userId: String, isOnline: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        dao.markAllNotificationsRead(userId)
        if (!isOnline) return@withContext Result.success(Unit)
        val payload = PostgrestCodec.mapAdapter.toJson(mapOf("is_read" to true))
        executeAuthorizedWrite("PATCH", "${baseUrl()}/rest/v1/notifications?user_id=eq.$userId&is_read=eq.false", payload)
        Result.success(Unit)
    }

    suspend fun fetchNotificationsSince(userId: String, sinceMillis: Long?): Result<List<NotificationEntity>> =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append("${baseUrl()}/rest/v1/notifications?user_id=eq.$userId&order=created_at.desc&select=*")
                if (sinceMillis != null && sinceMillis > 0L) {
                    append("&created_at=gt.${PostgrestCodec.formatTimestampIso(sinceMillis)}")
                }
            }
            when (val res = executeAuthorizedGet(url)) {
                is HttpCallResult.Success -> {
                    val items = PostgrestCodec.parseList(res.body).mapNotNull { PostgrestCodec.toNotification(it) }
                    if (items.isNotEmpty()) dao.upsertNotifications(items)
                    Result.success(items)
                }
                is HttpCallResult.Failure -> Result.failure(IllegalStateException(res.message))
            }
        }

    suspend fun clearMustChangePin(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        dao.updateMustChangePin(userId, false)
        val patch = PostgrestCodec.mapAdapter.toJson(
            mapOf(
                "must_change_pin" to false,
                "updated_at" to PostgrestCodec.formatTimestampIso(clockMillis())
            )
        )
        when (val res = executeAuthorizedWrite("PATCH", "${baseUrl()}/rest/v1/profiles?id=eq.$userId", patch)) {
            is HttpCallResult.Success -> Result.success(Unit)
            is HttpCallResult.Failure -> Result.failure(IllegalStateException(res.message))
        }
    }

    // Admin Endpoints (enforced server-side by is_admin() RLS / Edge Function)
    suspend fun adminResetUserPin(targetUserId: String, temporaryPin: String): Result<Unit> {
        val res = authClient.adminResetUserPin(targetUserId, temporaryPin)
        if (res.isSuccess) {
            dao.updateMustChangePin(targetUserId, true)
        }
        return res
    }

    suspend fun adminSetVerificationStatus(userId: String, status: String, reason: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val patch = PostgrestCodec.mapAdapter.toJson(
                mapOf(
                    "verification_status" to status,
                    "rejection_reason" to reason.trim(),
                    "updated_at" to PostgrestCodec.formatTimestampIso(clockMillis())
                )
            )
            when (val res = executeAuthorizedWrite("PATCH", "${baseUrl()}/rest/v1/profiles?id=eq.$userId", patch)) {
                is HttpCallResult.Success -> {
                    dao.getProfileById(userId)?.let {
                        dao.upsertProfile(it.copy(verificationStatus = status, rejectionReason = reason.trim()))
                    }
                    val notifJson = PostgrestCodec.mapAdapter.toJson(
                        mapOf(
                            "user_id" to userId,
                            "type" to "verification",
                            "title_darija" to if (status == VerificationStatus.VERIFIED) "مبروك! تأكد الحساب ديالك" else "تحديث حالة الحساب",
                            "title_ar" to if (status == VerificationStatus.VERIFIED) "تم توثيق حسابك" else "تحديث حالة التوثيق",
                            "title_fr" to if (status == VerificationStatus.VERIFIED) "Compte vérifié !" else "Statut de vérification mis à jour",
                            "body_text" to reason.ifBlank { status },
                            "related_entity_id" to userId
                        )
                    )
                    executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/notifications", notifJson)
                    Result.success(Unit)
                }
                is HttpCallResult.Failure -> Result.failure(IllegalStateException(res.message))
            }
        }

    suspend fun adminAddProduct(product: ProductEntity): Result<Unit> = withContext(Dispatchers.IO) {
        val prodMap = mapOf(
            "id" to product.id,
            "category_id" to product.categoryId,
            "name_darija" to product.nameDarija,
            "name_ar" to product.nameAr,
            "name_fr" to product.nameFr,
            "allowed_units" to product.allowedUnitsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            "image_key" to product.imageKey,
            "is_active" to true
        )
        when (val res = executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/products?on_conflict=id", PostgrestCodec.mapAdapter.toJson(prodMap), "resolution=merge-duplicates")) {
            is HttpCallResult.Success -> {
                val parsedSchema = runCatching { PostgrestCodec.listMapAdapter.fromJson(product.attributeSchemaJson) }.getOrNull() ?: emptyList()
                val schemaMap = mapOf(
                    "product_id" to product.id,
                    "schema_json" to parsedSchema,
                    "version" to 1
                )
                executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/product_attribute_schemas?on_conflict=product_id,version", PostgrestCodec.mapAdapter.toJson(schemaMap), "resolution=merge-duplicates")
                dao.upsertProduct(product)
                Result.success(Unit)
            }
            is HttpCallResult.Failure -> Result.failure(IllegalStateException(res.message))
        }
    }

    suspend fun adminAddReferencePrice(ref: ReferencePriceEntity): Result<Unit> = withContext(Dispatchers.IO) {
        val payload = PostgrestCodec.mapAdapter.toJson(
            mapOf(
                "id" to ensureUuid(ref.id),
                "product_id" to ref.productId,
                "region_id" to ref.regionId,
                "unit" to ref.unit,
                "price_mad" to ref.priceMad,
                "recorded_date" to PostgrestCodec.formatDateIso(ref.recordedEpochDay),
                "source" to ref.source
            )
        )
        when (val res = executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/reference_prices", payload)) {
            is HttpCallResult.Success -> {
                dao.upsertReferencePrice(ref)
                Result.success(Unit)
            }
            is HttpCallResult.Failure -> Result.failure(IllegalStateException(res.message))
        }
    }

    suspend fun adminSetProposalStatus(proposal: ProductProposalEntity, status: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val patch = PostgrestCodec.mapAdapter.toJson(mapOf("status" to status))
            when (val res = executeAuthorizedWrite("PATCH", "${baseUrl()}/rest/v1/product_proposals?id=eq.${proposal.id}", patch)) {
                is HttpCallResult.Success -> {
                    dao.upsertProductProposal(proposal.copy(status = status))
                    Result.success(Unit)
                }
                is HttpCallResult.Failure -> Result.failure(IllegalStateException(res.message))
            }
        }

    suspend fun adminResolveReport(report: ReportEntity): Result<Unit> = withContext(Dispatchers.IO) {
        val patch = PostgrestCodec.mapAdapter.toJson(mapOf("status" to "resolved"))
        when (val res = executeAuthorizedWrite("PATCH", "${baseUrl()}/rest/v1/reports?id=eq.${report.id}", patch)) {
            is HttpCallResult.Success -> {
                dao.upsertReport(report.copy(status = "resolved"))
                Result.success(Unit)
            }
            is HttpCallResult.Failure -> Result.failure(IllegalStateException(res.message))
        }
    }

    suspend fun insertOfferPhotoRecord(offerId: String, storagePath: String, sortOrder: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            val payload = PostgrestCodec.mapAdapter.toJson(
                mapOf(
                    "offer_id" to offerId,
                    "storage_path" to storagePath,
                    "sort_order" to sortOrder
                )
            )
            when (val res = executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/offer_photos", payload)) {
                is HttpCallResult.Success -> Result.success(Unit)
                is HttpCallResult.Failure -> Result.failure(IllegalStateException(res.message))
            }
        }

    suspend fun updateOfferMediaRemote(offerId: String, mediaStatus: String, voiceStoragePath: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            val map = buildMap<String, Any?> {
                put("media_status", mediaStatus)
                put("updated_at", PostgrestCodec.formatTimestampIso(clockMillis()))
                if (voiceStoragePath != null) put("voice_note_url", voiceStoragePath)
            }
            when (val res = executeAuthorizedWrite("PATCH", "${baseUrl()}/rest/v1/offers?id=eq.$offerId", PostgrestCodec.mapAdapter.toJson(map))) {
                is HttpCallResult.Success -> Result.success(Unit)
                is HttpCallResult.Failure -> Result.failure(IllegalStateException(res.message))
            }
        }

    suspend fun syncPendingItemsNow(): SyncRunSummary = withContext(Dispatchers.IO) {
        val now = clockMillis()
        val dueItems = dao.getDuePendingSyncQueue(now)
        var syncedCount = 0

        for (item in dueItems) {
            val res = dispatchSyncItem(item)
            when (res) {
                is HttpCallResult.Success -> {
                    when (item.entityType) {
                        "offer" -> dao.updateOfferSyncState(item.entityId, SyncState.SYNCED)
                        "buy_request" -> dao.updateBuyRequestSyncState(item.entityId, SyncState.SYNCED)
                        "deal", "deal_action" -> dao.updateDealSyncState(item.entityId, SyncState.SYNCED)
                    }
                    dao.removeSyncItem(item.id)
                    syncedCount++
                }
                is HttpCallResult.Failure -> {
                    val nextRetries = item.retryCount + 1
                    if (!res.isTransient || nextRetries >= MAX_SYNC_RETRIES) {
                        dao.enqueueSync(
                            item.copy(
                                status = SyncState.FAILED,
                                retryCount = nextRetries,
                                lastError = res.message.take(180),
                                updatedAt = now
                            )
                        )
                    } else {
                        val backoff = computeBackoffMillis(nextRetries)
                        dao.enqueueSync(
                            item.copy(
                                retryCount = nextRetries,
                                nextRetryAt = now + backoff,
                                lastError = res.message.take(180),
                                updatedAt = now
                            )
                        )
                    }
                }
            }
        }

        val failedCount = dao.getFailedSyncQueue().size
        val remainingPending = dao.getPendingSyncQueue().size
        SyncRunSummary(syncedCount, failedCount, remainingPending)
    }

    suspend fun retryFailedSyncItems(): SyncRunSummary = withContext(Dispatchers.IO) {
        dao.resetFailedSyncItems(clockMillis())
        syncPendingItemsNow()
    }

    private suspend fun dispatchSyncItem(item: SyncQueueEntity): HttpCallResult {
        return when (item.entityType) {
            "offer" -> {
                if (item.action == "update_status") {
                    executeAuthorizedWrite("PATCH", "${baseUrl()}/rest/v1/offers?id=eq.${item.entityId}", item.payloadJson)
                } else {
                    executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/offers?on_conflict=id", item.payloadJson, "resolution=merge-duplicates")
                }
            }
            "buy_request" -> executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/buy_requests?on_conflict=id", item.payloadJson, "resolution=merge-duplicates")
            "deal" -> executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/deals?on_conflict=id", item.payloadJson, "resolution=merge-duplicates")
            "deal_action" -> executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/rpc/deal_action", item.payloadJson)
            "profile" -> executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/profiles?on_conflict=id", item.payloadJson, "resolution=merge-duplicates")
            "rating" -> executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/ratings", item.payloadJson)
            "saved_filter" -> {
                if (item.action == "delete") {
                    executeAuthorizedWrite("DELETE", "${baseUrl()}/rest/v1/saved_filters?id=eq.${item.entityId}", null)
                } else {
                    executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/saved_filters", item.payloadJson)
                }
            }
            "product_proposal" -> executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/product_proposals", item.payloadJson)
            "report" -> executeAuthorizedWrite("POST", "${baseUrl()}/rest/v1/reports", item.payloadJson)
            else -> HttpCallResult.Success(200, "{}", null)
        }
    }

    private suspend fun enqueueSyncItem(entityType: String, entityId: String, action: String, payloadJson: String) {
        val now = clockMillis()
        dao.enqueueSync(
            SyncQueueEntity(
                id = UUID.randomUUID().toString(),
                entityType = entityType,
                entityId = entityId,
                action = action,
                payloadJson = payloadJson,
                status = SyncState.PENDING_SYNC,
                retryCount = 0,
                nextRetryAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private suspend fun mergeProfilesLww(remoteList: List<ProfileEntity>) {
        val toUpsert = remoteList.map { remote ->
            val local = dao.getProfileById(remote.id)
            if (local != null && local.updatedAt > remote.updatedAt) {
                // Keep newer local edits unless remote updated verification/rating fields
                if (local.verificationStatus == remote.verificationStatus && local.ratingCount == remote.ratingCount) {
                    local
                } else remote
            } else {
                // Preserve known phone if public_profiles masked it only if local isn't stale?
                // Security: Always respect server phone-masking on cross-user reads!
                remote
            }
        }
        if (toUpsert.isNotEmpty()) dao.upsertProfiles(toUpsert)
    }

    private suspend fun mergeOffersLww(remoteList: List<OfferEntity>) {
        val toUpsert = remoteList.map { remote ->
            val local = dao.getOfferById(remote.id)
            when {
                local != null && local.syncState == SyncState.PENDING_SYNC && local.updatedAt > remote.updatedAt -> local
                local != null && local.photoUrisCsv.isNotBlank() && remote.photoUrisCsv.isBlank() && local.mediaStatus == MediaStatus.UPLOADING ->
                    remote.copy(photoUrisCsv = local.photoUrisCsv, voiceNotePath = local.voiceNotePath)
                else -> remote
            }
        }
        if (toUpsert.isNotEmpty()) dao.upsertOffers(toUpsert)
    }

    private suspend fun mergeBuyRequestsLww(remoteList: List<BuyRequestEntity>) {
        val toUpsert = remoteList.map { remote ->
            val local = dao.getBuyRequestById(remote.id)
            if (local != null && local.syncState == SyncState.PENDING_SYNC && local.updatedAt > remote.updatedAt) local else remote
        }
        if (toUpsert.isNotEmpty()) dao.upsertBuyRequests(toUpsert)
    }

    private suspend fun mergeDealsLww(remoteList: List<DealEntity>) {
        val toUpsert = remoteList.map { remote ->
            val local = dao.getDealById(remote.id)
            if (local != null && local.syncState == SyncState.PENDING_SYNC && local.updatedAt > remote.updatedAt) local else remote
        }
        if (toUpsert.isNotEmpty()) dao.upsertDeals(toUpsert)
    }

    private fun applyOptimisticDealUpdate(
        deal: DealEntity,
        action: String,
        newPrice: Double?,
        actorRole: String
    ): DealEntity = when (action) {
        "counter" -> deal.copy(
            proposedPrice = newPrice ?: deal.proposedPrice,
            status = DealStatus.COUNTERED,
            lastActorRole = actorRole,
            updatedAt = clockMillis()
        )
        "accept" -> deal.copy(
            agreedPrice = deal.proposedPrice,
            status = DealStatus.ACCEPTED,
            lastActorRole = actorRole,
            updatedAt = clockMillis()
        )
        "cancel" -> deal.copy(
            status = DealStatus.CANCELLED,
            lastActorRole = actorRole,
            updatedAt = clockMillis()
        )
        "complete" -> com.example.core.domain.DealStateMachine.applyConfirmation(deal, actorRole)
        else -> deal
    }

    private suspend fun executeAuthorizedGet(
        url: String,
        rangeHeader: String? = null,
        preferCount: Boolean = false
    ): HttpCallResult {
        return executeWithTokenRefresh { token ->
            val builder = Request.Builder()
                .url(url)
                .header("apikey", anonKey())
                .header("Authorization", "Bearer $token")
                .get()
            if (rangeHeader != null) builder.header("Range", rangeHeader)
            if (preferCount) builder.header("Prefer", "count=exact")
            builder.build()
        }
    }

    private suspend fun executeAuthorizedWrite(
        method: String,
        url: String,
        jsonBody: String?,
        prefer: String? = null
    ): HttpCallResult {
        return executeWithTokenRefresh { token ->
            val builder = Request.Builder()
                .url(url)
                .header("apikey", anonKey())
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
            if (prefer != null) builder.header("Prefer", prefer)
            when (method) {
                "POST" -> builder.post((jsonBody ?: "{}").toRequestBody(jsonMedia))
                "PATCH" -> builder.patch((jsonBody ?: "{}").toRequestBody(jsonMedia))
                "DELETE" -> builder.delete()
                else -> builder.method(method, jsonBody?.toRequestBody(jsonMedia))
            }
            builder.build()
        }
    }

    private suspend fun executeWithTokenRefresh(buildRequest: (String) -> Request): HttpCallResult {
        if (!isRemoteConfigured()) {
            return HttpCallResult.Failure(0, "Supabase not configured", isTransient = false)
        }
        val tokenRes = authClient.getValidAccessToken()
        val token = tokenRes.getOrElse { err ->
            val authErr = err as? AuthException
            if (authErr?.code == AuthErrorCode.SESSION_EXPIRED) {
                onSessionExpired()
                return HttpCallResult.Failure(401, "Session expired", isTransient = false, isSessionExpired = true)
            }
            return HttpCallResult.Failure(0, err.message ?: "Auth failure", isTransient = authErr?.code == AuthErrorCode.NO_NETWORK)
        }

        val first = runHttpCall(buildRequest(token))
        if (first is HttpCallResult.Failure && first.code == 401) {
            val refreshed = authClient.refreshSession()
            val nextToken = refreshed.getOrNull()?.accessToken
            if (nextToken != null) {
                return runHttpCall(buildRequest(nextToken))
            }
            if ((refreshed.exceptionOrNull() as? AuthException)?.code == AuthErrorCode.SESSION_EXPIRED) {
                onSessionExpired()
                return HttpCallResult.Failure(401, "Session expired", isTransient = false, isSessionExpired = true)
            }
        }
        return first
    }

    private fun runHttpCall(request: Request): HttpCallResult {
        return try {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    HttpCallResult.Success(resp.code, body, resp.header("Content-Range"))
                } else {
                    val isTransient = resp.code >= 500 || resp.code == 429 || resp.code == 408
                    val msg = parsePostgrestErrorMessage(body).ifBlank { "HTTP ${resp.code}" }
                    HttpCallResult.Failure(resp.code, msg, isTransient = isTransient)
                }
            }
        } catch (io: IOException) {
            HttpCallResult.Failure(0, "Network unavailable", isTransient = true)
        } catch (t: Throwable) {
            HttpCallResult.Failure(0, t.message ?: "Request error", isTransient = false)
        }
    }

    private fun parsePostgrestErrorMessage(raw: String): String {
        val obj = PostgrestCodec.parseObject(raw) ?: return ""
        return (obj["message"] as? String)
            ?: (obj["error_description"] as? String)
            ?: (obj["hint"] as? String)
            ?: ""
    }

    private fun parseContentRange(header: String?): Pair<Int?, Int?> {
        if (header.isNullOrBlank()) return null to null
        // Format: "0-19/42" or "*/0"
        val slashParts = header.split("/")
        val total = slashParts.getOrNull(1)?.toIntOrNull()
        val end = slashParts.getOrNull(0)?.split("-")?.getOrNull(1)?.toIntOrNull()
        return end to total
    }

    private fun ensureUuid(raw: String): String {
        return runCatching { UUID.fromString(raw).toString() }.getOrElse { UUID.randomUUID().toString() }
    }

    companion object {
        const val MAX_SYNC_RETRIES = 5

        fun computeBackoffMillis(retryCount: Int): Long {
            val exp = retryCount.coerceIn(1, 6) - 1
            return minOf(300_000L, 5_000L * (1L shl exp))
        }
    }
}
