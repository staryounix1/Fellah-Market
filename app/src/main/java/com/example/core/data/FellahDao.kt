package com.example.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FellahDao {
    // Profiles & Verification
    @Query("SELECT * FROM profiles ORDER BY createdAt DESC")
    fun observeAllProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    fun observeProfile(id: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE phone = :phone LIMIT 1")
    suspend fun getProfileByPhone(phone: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfiles(profiles: List<ProfileEntity>)

    @Query("UPDATE profiles SET verificationDocPath = :docPath, updatedAt = :updatedAt WHERE id = :userId")
    suspend fun updateProfileVerificationDoc(userId: String, docPath: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE profiles SET mustChangePin = :mustChange, updatedAt = :updatedAt WHERE id = :userId")
    suspend fun updateMustChangePin(userId: String, mustChange: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFarmerDetails(details: FarmerDetailsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFarmerDetailsList(details: List<FarmerDetailsEntity>)

    @Query("SELECT * FROM farmer_details")
    fun observeAllFarmerDetails(): Flow<List<FarmerDetailsEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBuyerDetails(details: BuyerDetailsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBuyerDetailsList(details: List<BuyerDetailsEntity>)

    @Query("SELECT * FROM buyer_details")
    fun observeAllBuyerDetails(): Flow<List<BuyerDetailsEntity>>

    // Regions, Categories, Products
    @Query("SELECT * FROM regions WHERE isActive = 1")
    fun observeRegions(): Flow<List<RegionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRegion(region: RegionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRegions(regions: List<RegionEntity>)

    @Query("SELECT * FROM categories WHERE isActive = 1 ORDER BY sortOrder ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<CategoryEntity>)

    @Query("SELECT * FROM products WHERE isActive = 1")
    fun observeProducts(): Flow<List<ProductEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProduct(product: ProductEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProducts(products: List<ProductEntity>)

    // Offers
    @Query("SELECT * FROM offers ORDER BY createdAt DESC")
    fun observeAllOffers(): Flow<List<OfferEntity>>

    @Query("SELECT * FROM offers WHERE id = :id LIMIT 1")
    suspend fun getOfferById(id: String): OfferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOffer(offer: OfferEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOffers(offers: List<OfferEntity>)

    @Query("UPDATE offers SET syncState = :syncState WHERE id = :offerId")
    suspend fun updateOfferSyncState(offerId: String, syncState: String)

    @Query("UPDATE offers SET mediaStatus = :mediaStatus, updatedAt = :updatedAt WHERE id = :offerId")
    suspend fun updateOfferMediaStatus(offerId: String, mediaStatus: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE offers SET mediaStatus = :mediaStatus, photoUrisCsv = :photoUrisCsv, voiceNotePath = :voiceNotePath, updatedAt = :updatedAt WHERE id = :offerId")
    suspend fun updateOfferMediaComplete(
        offerId: String,
        mediaStatus: String,
        photoUrisCsv: String,
        voiceNotePath: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    // Buy Requests (Wanted)
    @Query("SELECT * FROM buy_requests ORDER BY createdAt DESC")
    fun observeAllBuyRequests(): Flow<List<BuyRequestEntity>>

    @Query("SELECT * FROM buy_requests WHERE id = :id LIMIT 1")
    suspend fun getBuyRequestById(id: String): BuyRequestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBuyRequest(request: BuyRequestEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBuyRequests(requests: List<BuyRequestEntity>)

    @Query("UPDATE buy_requests SET syncState = :syncState WHERE id = :requestId")
    suspend fun updateBuyRequestSyncState(requestId: String, syncState: String)

    // Deals & History
    @Query("SELECT * FROM deals ORDER BY updatedAt DESC")
    fun observeAllDeals(): Flow<List<DealEntity>>

    @Query("SELECT * FROM deals WHERE id = :id LIMIT 1")
    suspend fun getDealById(id: String): DealEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeal(deal: DealEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeals(deals: List<DealEntity>)

    @Query("UPDATE deals SET syncState = :syncState WHERE id = :dealId")
    suspend fun updateDealSyncState(dealId: String, syncState: String)

    @Query("SELECT * FROM deal_status_history ORDER BY createdAt ASC")
    fun observeAllDealHistory(): Flow<List<DealStatusHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDealHistory(entry: DealStatusHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDealHistoryList(entries: List<DealStatusHistoryEntity>)

    // Ratings
    @Query("SELECT * FROM ratings ORDER BY createdAt DESC")
    fun observeAllRatings(): Flow<List<RatingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRating(rating: RatingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRatings(ratings: List<RatingEntity>)

    // Notifications
    @Query("SELECT * FROM notifications WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeUserNotifications(userId: String): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE userId = :userId")
    suspend fun getUserNotificationsSnapshot(userId: String): List<NotificationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: NotificationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotifications(notifications: List<NotificationEntity>)

    @Query("UPDATE notifications SET isRead = 1 WHERE userId = :userId")
    suspend fun markAllNotificationsRead(userId: String)

    // Saved Filters
    @Query("SELECT * FROM saved_filters WHERE buyerId = :buyerId ORDER BY createdAt DESC")
    fun observeSavedFilters(buyerId: String): Flow<List<SavedFilterEntity>>

    @Query("SELECT * FROM saved_filters")
    suspend fun getAllSavedFilters(): List<SavedFilterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSavedFilter(filter: SavedFilterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSavedFilters(filters: List<SavedFilterEntity>)

    @Query("DELETE FROM saved_filters WHERE id = :filterId")
    suspend fun deleteSavedFilter(filterId: String)

    // Favorite offers (local-first; account scoped)
    @Query("SELECT * FROM favorite_offers WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeFavoriteOffers(userId: String): Flow<List<FavoriteOfferEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavoriteOffer(favorite: FavoriteOfferEntity)

    @Query("DELETE FROM favorite_offers WHERE userId = :userId AND offerId = :offerId")
    suspend fun deleteFavoriteOffer(userId: String, offerId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_offers WHERE userId = :userId AND offerId = :offerId)")
    suspend fun isFavoriteOffer(userId: String, offerId: String): Boolean

    // Reference Prices
    @Query("SELECT * FROM reference_prices ORDER BY recordedEpochDay ASC")
    fun observeReferencePrices(): Flow<List<ReferencePriceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReferencePrice(price: ReferencePriceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReferencePrices(prices: List<ReferencePriceEntity>)

    // Proposals, Reports, Analytics, Sync Queue
    @Query("SELECT * FROM product_proposals ORDER BY createdAt DESC")
    fun observeProductProposals(): Flow<List<ProductProposalEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProductProposal(proposal: ProductProposalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProductProposals(proposals: List<ProductProposalEntity>)

    @Query("SELECT * FROM reports ORDER BY createdAt DESC")
    fun observeReports(): Flow<List<ReportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReport(report: ReportEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReports(reports: List<ReportEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalyticsEvent(event: AnalyticsEventEntity)

    @Query("SELECT * FROM analytics_events ORDER BY createdAt DESC LIMIT 50")
    fun observeAnalyticsEvents(): Flow<List<AnalyticsEventEntity>>

    @Query("SELECT * FROM sync_queue WHERE status = 'pending_sync' ORDER BY createdAt ASC")
    fun observePendingSyncQueue(): Flow<List<SyncQueueEntity>>

    @Query("SELECT * FROM sync_queue WHERE status = 'failed' ORDER BY updatedAt DESC")
    fun observeFailedSyncQueue(): Flow<List<SyncQueueEntity>>

    @Query("SELECT * FROM sync_queue WHERE status = 'pending_sync' ORDER BY createdAt ASC")
    suspend fun getPendingSyncQueue(): List<SyncQueueEntity>

    @Query("SELECT * FROM sync_queue WHERE status = 'pending_sync' AND nextRetryAt <= :nowMillis ORDER BY createdAt ASC")
    suspend fun getDuePendingSyncQueue(nowMillis: Long): List<SyncQueueEntity>

    @Query("SELECT * FROM sync_queue WHERE status = 'failed' ORDER BY updatedAt DESC")
    suspend fun getFailedSyncQueue(): List<SyncQueueEntity>

    @Query("UPDATE sync_queue SET status = 'pending_sync', retryCount = 0, nextRetryAt = :nowMillis, lastError = '', updatedAt = :nowMillis WHERE status = 'failed'")
    suspend fun resetFailedSyncItems(nowMillis: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueSync(item: SyncQueueEntity)

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun removeSyncItem(id: String)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun countCategories(): Int
}
