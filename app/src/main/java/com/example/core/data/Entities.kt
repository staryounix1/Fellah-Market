package com.example.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey

object UserRole {
    const val FARMER = "farmer"
    const val BUYER = "buyer"
    const val ADMIN = "admin"
}

object VerificationStatus {
    const val PENDING = "pending"
    const val VERIFIED = "verified"
    const val REJECTED = "rejected"
}

object OfferStatus {
    const val ACTIVE = "active"
    const val RESERVED = "reserved"
    const val SOLD = "sold"
    const val PAUSED = "paused"
    const val EXPIRED = "expired"
}

object DealStatus {
    const val REQUESTED = "requested"
    const val COUNTERED = "countered"
    const val ACCEPTED = "accepted"
    const val COMPLETED = "completed"
    const val CANCELLED = "cancelled"
}

object SyncState {
    const val SYNCED = "synced"
    const val PENDING_SYNC = "pending_sync"
    const val FAILED = "failed"
}

object MediaStatus {
    const val READY = "ready"
    const val UPLOADING = "uploading_media"
    const val FAILED = "failed"
}

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val phone: String,
    val fullName: String,
    val role: String, // farmer | buyer | admin
    val verificationStatus: String, // pending | verified | rejected
    val rejectionReason: String = "",
    val verificationDocPath: String = "",
    val mustChangePin: Boolean = false,
    val regionId: String,
    val city: String,
    val avatarUrl: String = "",
    val ratingAvg: Double = 0.0,
    val ratingCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "farmer_details")
data class FarmerDetailsEntity(
    @PrimaryKey val userId: String,
    val farmOrCoopName: String,
    val mainCategoryIdsCsv: String // comma-separated category IDs
)

@Entity(tableName = "buyer_details")
data class BuyerDetailsEntity(
    @PrimaryKey val userId: String,
    val businessName: String,
    val businessType: String, // restaurant | hotel | grocery | wholesaler | cooperative
    val interestCategoryIdsCsv: String
)

@Entity(tableName = "regions")
data class RegionEntity(
    @PrimaryKey val id: String,
    val nameDarija: String,
    val nameAr: String,
    val nameFr: String,
    val citiesCsv: String,
    val isDefault: Boolean = false,
    val isActive: Boolean = true
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val nameDarija: String,
    val nameAr: String,
    val nameFr: String,
    val iconKey: String,
    val sortOrder: Int,
    val isActive: Boolean = true
)

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val categoryId: String,
    val nameDarija: String,
    val nameAr: String,
    val nameFr: String,
    val allowedUnitsCsv: String, // kg,ton,crate
    val attributeSchemaJson: String, // JSON array of DynamicFormField
    val imageKey: String, // tomato | citrus | honey | generic
    val isActive: Boolean = true
)

@Entity(tableName = "offers")
data class OfferEntity(
    @PrimaryKey val id: String,
    val farmerId: String,
    val productId: String,
    val categoryId: String,
    val regionId: String,
    val city: String,
    val quantity: Double,
    val unit: String,
    val askingPrice: Double,
    val readyDateEpochDay: Long,
    val attributesJson: String, // JSON object of fieldKey -> value
    val photoUrisCsv: String, // local compressed file paths, remote storage paths, or drawable keys
    val voiceNotePath: String = "",
    val voiceDurationSec: Int = 0,
    val mediaStatus: String = MediaStatus.READY, // ready | uploading_media | failed
    val expiresAt: Long = System.currentTimeMillis() + 14L * 86_400_000L,
    val status: String = OfferStatus.ACTIVE,
    val syncState: String = SyncState.SYNCED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "buy_requests")
data class BuyRequestEntity(
    @PrimaryKey val id: String,
    val buyerId: String,
    val productId: String,
    val categoryId: String,
    val regionId: String,
    val city: String,
    val quantity: Double,
    val unit: String,
    val maxPrice: Double,
    val neededDateEpochDay: Long,
    val notes: String = "",
    val status: String = "open", // open | fulfilled | closed
    val syncState: String = SyncState.SYNCED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "deals")
data class DealEntity(
    @PrimaryKey val id: String,
    val offerId: String?,
    val buyRequestId: String?,
    val farmerId: String,
    val buyerId: String,
    val productId: String,
    val quantity: Double,
    val unit: String,
    val proposedPrice: Double,
    val agreedPrice: Double? = null,
    val deliveryType: String, // pickup | delivery
    val preferredDateEpochDay: Long,
    val note: String = "",
    val status: String = DealStatus.REQUESTED,
    val lastActorRole: String = UserRole.BUYER,
    val farmerConfirmedCompletion: Boolean = false,
    val buyerConfirmedCompletion: Boolean = false,
    val syncState: String = SyncState.SYNCED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "deal_status_history")
data class DealStatusHistoryEntity(
    @PrimaryKey val id: String,
    val dealId: String,
    val status: String,
    val actorId: String,
    val note: String,
    val priceAtStep: Double,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "ratings")
data class RatingEntity(
    @PrimaryKey val id: String,
    val dealId: String,
    val fromUserId: String,
    val toUserId: String,
    val stars: Int,
    val comment: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val type: String,
    val titleDarija: String,
    val titleAr: String,
    val titleFr: String,
    val bodyText: String,
    val relatedEntityId: String = "",
    val isRead: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "saved_filters")
data class SavedFilterEntity(
    @PrimaryKey val id: String,
    val buyerId: String,
    val label: String,
    val categoryId: String? = null,
    val productId: String? = null,
    val city: String? = null,
    val maxPrice: Double? = null,
    val minQty: Double? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reference_prices")
data class ReferencePriceEntity(
    @PrimaryKey val id: String,
    val productId: String,
    val regionId: String,
    val unit: String,
    val priceMad: Double,
    val recordedEpochDay: Long,
    val source: String = "wholesale_market" // wholesale_market | admin
)

@Entity(tableName = "product_proposals")
data class ProductProposalEntity(
    @PrimaryKey val id: String,
    val farmerId: String,
    val proposedName: String,
    val categoryId: String,
    val suggestedUnit: String,
    val notes: String = "",
    val status: String = "pending", // pending | approved | rejected
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reports")
data class ReportEntity(
    @PrimaryKey val id: String,
    val reporterId: String,
    val targetType: String, // offer | user
    val targetId: String,
    val reason: String,
    val status: String = "open", // open | resolved
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "analytics_events")
data class AnalyticsEventEntity(
    @PrimaryKey val id: String,
    val eventName: String,
    val userId: String,
    val metadataJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sync_queue")
data class SyncQueueEntity(
    @PrimaryKey val id: String,
    val entityType: String, // offer | buy_request | deal | deal_action | profile | rating | saved_filter | product_proposal | report
    val entityId: String,
    val action: String, // upsert | update_status | rpc | delete
    val payloadJson: String,
    val status: String = SyncState.PENDING_SYNC, // pending_sync | failed
    val retryCount: Int = 0,
    val nextRetryAt: Long = 0L,
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)


/** A user's locally saved offer. This remains available offline and is scoped per account. */
@Entity(tableName = "favorite_offers", primaryKeys = ["userId", "offerId"])
data class FavoriteOfferEntity(
    val userId: String,
    val offerId: String,
    val createdAt: Long = System.currentTimeMillis()
)
