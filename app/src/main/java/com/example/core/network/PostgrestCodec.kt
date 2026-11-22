package com.example.core.network

import com.example.core.data.*
import com.example.core.domain.PriceTrendPoint
import com.example.core.domain.ProductMarketSummary
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

object PostgrestCodec {
    val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val listMapType = Types.newParameterizedType(List::class.java, mapType)

    val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)
    val listMapAdapter = moshi.adapter<List<Map<String, Any?>>>(listMapType)

    fun parseList(json: String): List<Map<String, Any?>> =
        runCatching { listMapAdapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())

    fun parseObject(json: String): Map<String, Any?>? =
        runCatching { mapAdapter.fromJson(json) }.getOrNull()

    fun parseTimestampMillis(raw: Any?, fallback: Long = System.currentTimeMillis()): Long {
        val str = raw as? String ?: return fallback
        return runCatching { Instant.parse(str).toEpochMilli() }.getOrElse { fallback }
    }

    fun formatTimestampIso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    fun parseEpochDay(raw: Any?, fallback: Long = LocalDate.now(ZoneOffset.UTC).toEpochDay()): Long {
        val str = raw as? String ?: return fallback
        return runCatching { LocalDate.parse(str.take(10)).toEpochDay() }.getOrElse { fallback }
    }

    fun formatDateIso(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

    @Suppress("UNCHECKED_CAST")
    fun stringList(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        is String -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    fun toRegion(m: Map<String, Any?>): RegionEntity? {
        val id = m["id"] as? String ?: return null
        return RegionEntity(
            id = id,
            nameDarija = m["name_darija"] as? String ?: id,
            nameAr = m["name_ar"] as? String ?: id,
            nameFr = m["name_fr"] as? String ?: id,
            citiesCsv = stringList(m["cities"]).joinToString(","),
            isDefault = m["is_default"] as? Boolean ?: false,
            isActive = m["is_active"] as? Boolean ?: true
        )
    }

    fun toCategory(m: Map<String, Any?>): CategoryEntity? {
        val id = m["id"] as? String ?: return null
        return CategoryEntity(
            id = id,
            nameDarija = m["name_darija"] as? String ?: id,
            nameAr = m["name_ar"] as? String ?: id,
            nameFr = m["name_fr"] as? String ?: id,
            iconKey = m["icon_key"] as? String ?: "veg",
            sortOrder = (m["sort_order"] as? Number)?.toInt() ?: 0,
            isActive = m["is_active"] as? Boolean ?: true
        )
    }

    fun toProduct(m: Map<String, Any?>, schemaJsonByProductId: Map<String, String>): ProductEntity? {
        val id = m["id"] as? String ?: return null
        val embeddedSchemas = m["product_attribute_schemas"] as? List<*>
        val embeddedSchemaJson = (embeddedSchemas?.lastOrNull() as? Map<*, *>)?.get("schema_json")?.let { rawSchema ->
            when (rawSchema) {
                is String -> rawSchema
                else -> moshi.adapter(Any::class.java).toJson(rawSchema)
            }
        }
        return ProductEntity(
            id = id,
            categoryId = m["category_id"] as? String ?: "",
            nameDarija = m["name_darija"] as? String ?: id,
            nameAr = m["name_ar"] as? String ?: id,
            nameFr = m["name_fr"] as? String ?: id,
            allowedUnitsCsv = stringList(m["allowed_units"]).joinToString(","),
            attributeSchemaJson = embeddedSchemaJson ?: schemaJsonByProductId[id] ?: "[]",
            imageKey = m["image_key"] as? String ?: "hero",
            isActive = m["is_active"] as? Boolean ?: true
        )
    }

    fun toProfile(m: Map<String, Any?>): ProfileEntity? {
        val id = m["id"] as? String ?: return null
        return ProfileEntity(
            id = id,
            phone = (m["phone"] as? String).orEmpty(),
            fullName = (m["full_name"] as? String).orEmpty(),
            role = m["role"] as? String ?: UserRole.FARMER,
            verificationStatus = m["verification_status"] as? String ?: VerificationStatus.PENDING,
            rejectionReason = (m["rejection_reason"] as? String).orEmpty(),
            verificationDocPath = (m["verification_doc_path"] as? String).orEmpty(),
            mustChangePin = m["must_change_pin"] as? Boolean ?: false,
            regionId = m["region_id"] as? String ?: "reg_souss_massa",
            city = (m["city"] as? String).orEmpty(),
            avatarUrl = (m["avatar_url"] as? String).orEmpty(),
            ratingAvg = (m["rating_avg"] as? Number)?.toDouble() ?: 0.0,
            ratingCount = (m["rating_count"] as? Number)?.toInt() ?: 0,
            createdAt = parseTimestampMillis(m["created_at"]),
            updatedAt = parseTimestampMillis(m["updated_at"])
        )
    }

    fun toFarmerDetails(m: Map<String, Any?>): FarmerDetailsEntity? {
        val uid = m["user_id"] as? String ?: return null
        return FarmerDetailsEntity(
            userId = uid,
            farmOrCoopName = (m["farm_or_coop_name"] as? String).orEmpty(),
            mainCategoryIdsCsv = stringList(m["main_category_ids"]).joinToString(",")
        )
    }

    fun toBuyerDetails(m: Map<String, Any?>): BuyerDetailsEntity? {
        val uid = m["user_id"] as? String ?: return null
        return BuyerDetailsEntity(
            userId = uid,
            businessName = (m["business_name"] as? String).orEmpty(),
            businessType = m["business_type"] as? String ?: "restaurant",
            interestCategoryIdsCsv = stringList(m["interest_category_ids"]).joinToString(",")
        )
    }

    fun toOffer(m: Map<String, Any?>): OfferEntity? {
        val id = m["id"] as? String ?: return null
        val rawAttrs = m["attributes"]
        val attrsJson = when (rawAttrs) {
            is String -> rawAttrs
            is Map<*, *> -> moshi.adapter(Any::class.java).toJson(rawAttrs)
            else -> "{}"
        }
        val embeddedPhotos = (m["offer_photos"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.sortedBy { (it["sort_order"] as? Number)?.toInt() ?: 0 }
            ?.mapNotNull { it["storage_path"] as? String }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val photoCsv = embeddedPhotos.joinToString(",")
        return OfferEntity(
            id = id,
            farmerId = m["farmer_id"] as? String ?: return null,
            productId = m["product_id"] as? String ?: return null,
            categoryId = m["category_id"] as? String ?: "",
            regionId = m["region_id"] as? String ?: "reg_souss_massa",
            city = (m["city"] as? String).orEmpty(),
            quantity = (m["quantity"] as? Number)?.toDouble() ?: 0.0,
            unit = m["unit"] as? String ?: "kg",
            askingPrice = (m["asking_price"] as? Number)?.toDouble() ?: 0.0,
            readyDateEpochDay = parseEpochDay(m["ready_date"]),
            attributesJson = attrsJson,
            photoUrisCsv = photoCsv,
            voiceNotePath = (m["voice_note_url"] as? String).orEmpty(),
            voiceDurationSec = (m["voice_duration_sec"] as? Number)?.toInt() ?: 0,
            mediaStatus = m["media_status"] as? String ?: MediaStatus.READY,
            expiresAt = parseTimestampMillis(m["expires_at"], System.currentTimeMillis() + 14L * 86_400_000L),
            status = m["status"] as? String ?: OfferStatus.ACTIVE,
            syncState = SyncState.SYNCED,
            createdAt = parseTimestampMillis(m["created_at"]),
            updatedAt = parseTimestampMillis(m["updated_at"])
        )
    }

    fun offerToRemoteMap(offer: OfferEntity): Map<String, Any?> {
        val attrObj = runCatching { mapAdapter.fromJson(offer.attributesJson) }.getOrNull() ?: emptyMap<String, Any?>()
        val remoteVoice = offer.voiceNotePath.takeIf { it.isNotBlank() && !it.startsWith("/") } ?: ""
        return mapOf(
            "id" to offer.id,
            "farmer_id" to offer.farmerId,
            "product_id" to offer.productId,
            "category_id" to offer.categoryId,
            "region_id" to offer.regionId,
            "city" to offer.city,
            "quantity" to offer.quantity,
            "unit" to offer.unit,
            "asking_price" to offer.askingPrice,
            "ready_date" to formatDateIso(offer.readyDateEpochDay),
            "expires_at" to formatTimestampIso(offer.expiresAt),
            "attributes" to attrObj,
            "voice_note_url" to remoteVoice,
            "voice_duration_sec" to offer.voiceDurationSec,
            "media_status" to offer.mediaStatus,
            "status" to offer.status,
            "updated_at" to formatTimestampIso(offer.updatedAt)
        )
    }

    fun toBuyRequest(m: Map<String, Any?>): BuyRequestEntity? {
        val id = m["id"] as? String ?: return null
        return BuyRequestEntity(
            id = id,
            buyerId = m["buyer_id"] as? String ?: return null,
            productId = m["product_id"] as? String ?: return null,
            categoryId = m["category_id"] as? String ?: "",
            regionId = m["region_id"] as? String ?: "reg_souss_massa",
            city = (m["city"] as? String).orEmpty(),
            quantity = (m["quantity"] as? Number)?.toDouble() ?: 0.0,
            unit = m["unit"] as? String ?: "kg",
            maxPrice = (m["max_price"] as? Number)?.toDouble() ?: 0.0,
            neededDateEpochDay = parseEpochDay(m["needed_date"]),
            notes = (m["notes"] as? String).orEmpty(),
            status = m["status"] as? String ?: "open",
            syncState = SyncState.SYNCED,
            createdAt = parseTimestampMillis(m["created_at"]),
            updatedAt = parseTimestampMillis(m["updated_at"], parseTimestampMillis(m["created_at"]))
        )
    }

    fun buyRequestToRemoteMap(req: BuyRequestEntity): Map<String, Any?> = mapOf(
        "id" to req.id,
        "buyer_id" to req.buyerId,
        "product_id" to req.productId,
        "category_id" to req.categoryId,
        "region_id" to req.regionId,
        "city" to req.city,
        "quantity" to req.quantity,
        "unit" to req.unit,
        "max_price" to req.maxPrice,
        "needed_date" to formatDateIso(req.neededDateEpochDay),
        "notes" to req.notes,
        "status" to req.status,
        "updated_at" to formatTimestampIso(req.updatedAt)
    )

    fun toDeal(m: Map<String, Any?>): DealEntity? {
        val id = m["id"] as? String ?: return null
        return DealEntity(
            id = id,
            offerId = m["offer_id"] as? String,
            buyRequestId = m["buy_request_id"] as? String,
            farmerId = m["farmer_id"] as? String ?: return null,
            buyerId = m["buyer_id"] as? String ?: return null,
            productId = m["product_id"] as? String ?: return null,
            quantity = (m["quantity"] as? Number)?.toDouble() ?: 0.0,
            unit = m["unit"] as? String ?: "kg",
            proposedPrice = (m["proposed_price"] as? Number)?.toDouble() ?: 0.0,
            agreedPrice = (m["agreed_price"] as? Number)?.toDouble(),
            deliveryType = m["delivery_type"] as? String ?: "pickup",
            preferredDateEpochDay = parseEpochDay(m["preferred_date"]),
            note = (m["note"] as? String).orEmpty(),
            status = m["status"] as? String ?: DealStatus.REQUESTED,
            lastActorRole = m["last_actor_role"] as? String ?: UserRole.BUYER,
            farmerConfirmedCompletion = m["farmer_confirmed_completion"] as? Boolean ?: false,
            buyerConfirmedCompletion = m["buyer_confirmed_completion"] as? Boolean ?: false,
            syncState = SyncState.SYNCED,
            createdAt = parseTimestampMillis(m["created_at"]),
            updatedAt = parseTimestampMillis(m["updated_at"])
        )
    }

    fun dealToRemoteMap(deal: DealEntity): Map<String, Any?> = mapOf(
        "id" to deal.id,
        "offer_id" to deal.offerId,
        "buy_request_id" to deal.buyRequestId,
        "farmer_id" to deal.farmerId,
        "buyer_id" to deal.buyerId,
        "product_id" to deal.productId,
        "quantity" to deal.quantity,
        "unit" to deal.unit,
        "proposed_price" to deal.proposedPrice,
        "agreed_price" to deal.agreedPrice,
        "delivery_type" to deal.deliveryType,
        "preferred_date" to formatDateIso(deal.preferredDateEpochDay),
        "note" to deal.note,
        "status" to deal.status,
        "last_actor_role" to deal.lastActorRole,
        "farmer_confirmed_completion" to deal.farmerConfirmedCompletion,
        "buyer_confirmed_completion" to deal.buyerConfirmedCompletion,
        "updated_at" to formatTimestampIso(deal.updatedAt)
    )

    fun toDealHistory(m: Map<String, Any?>): DealStatusHistoryEntity? {
        val id = m["id"] as? String ?: return null
        return DealStatusHistoryEntity(
            id = id,
            dealId = m["deal_id"] as? String ?: return null,
            status = m["status"] as? String ?: return null,
            actorId = m["actor_id"] as? String ?: return null,
            note = (m["note"] as? String).orEmpty(),
            priceAtStep = (m["price_at_step"] as? Number)?.toDouble() ?: 0.0,
            createdAt = parseTimestampMillis(m["created_at"])
        )
    }

    fun toRating(m: Map<String, Any?>): RatingEntity? {
        val id = m["id"] as? String ?: return null
        return RatingEntity(
            id = id,
            dealId = m["deal_id"] as? String ?: return null,
            fromUserId = m["from_user_id"] as? String ?: return null,
            toUserId = m["to_user_id"] as? String ?: return null,
            stars = (m["stars"] as? Number)?.toInt() ?: 5,
            comment = (m["comment"] as? String).orEmpty(),
            createdAt = parseTimestampMillis(m["created_at"])
        )
    }

    fun toNotification(m: Map<String, Any?>): NotificationEntity? {
        val id = m["id"] as? String ?: return null
        return NotificationEntity(
            id = id,
            userId = m["user_id"] as? String ?: return null,
            type = m["type"] as? String ?: "info",
            titleDarija = (m["title_darija"] as? String).orEmpty(),
            titleAr = (m["title_ar"] as? String).orEmpty(),
            titleFr = (m["title_fr"] as? String).orEmpty(),
            bodyText = (m["body_text"] as? String).orEmpty(),
            relatedEntityId = (m["related_entity_id"] as? String).orEmpty(),
            isRead = m["is_read"] as? Boolean ?: false,
            createdAt = parseTimestampMillis(m["created_at"])
        )
    }

    fun toSavedFilter(m: Map<String, Any?>): SavedFilterEntity? {
        val id = m["id"] as? String ?: return null
        return SavedFilterEntity(
            id = id,
            buyerId = m["buyer_id"] as? String ?: return null,
            label = (m["label"] as? String).orEmpty(),
            categoryId = m["category_id"] as? String,
            productId = m["product_id"] as? String,
            city = m["city"] as? String,
            maxPrice = (m["max_price"] as? Number)?.toDouble(),
            minQty = (m["min_qty"] as? Number)?.toDouble(),
            createdAt = parseTimestampMillis(m["created_at"])
        )
    }

    fun toReferencePrice(m: Map<String, Any?>): ReferencePriceEntity? {
        val id = m["id"] as? String ?: return null
        return ReferencePriceEntity(
            id = id,
            productId = m["product_id"] as? String ?: return null,
            regionId = m["region_id"] as? String ?: return null,
            unit = m["unit"] as? String ?: "kg",
            priceMad = (m["price_mad"] as? Number)?.toDouble() ?: 0.0,
            recordedEpochDay = parseEpochDay(m["recorded_date"]),
            source = m["source"] as? String ?: "wholesale_market"
        )
    }

    fun toProductProposal(m: Map<String, Any?>): ProductProposalEntity? {
        val id = m["id"] as? String ?: return null
        return ProductProposalEntity(
            id = id,
            farmerId = m["farmer_id"] as? String ?: return null,
            proposedName = (m["proposed_name"] as? String).orEmpty(),
            categoryId = m["category_id"] as? String ?: "",
            suggestedUnit = m["suggested_unit"] as? String ?: "kg",
            notes = (m["notes"] as? String).orEmpty(),
            status = m["status"] as? String ?: "pending",
            createdAt = parseTimestampMillis(m["created_at"])
        )
    }

    fun toReport(m: Map<String, Any?>): ReportEntity? {
        val id = m["id"] as? String ?: return null
        return ReportEntity(
            id = id,
            reporterId = m["reporter_id"] as? String ?: return null,
            targetType = m["target_type"] as? String ?: "offer",
            targetId = m["target_id"] as? String ?: "",
            reason = (m["reason"] as? String).orEmpty(),
            status = m["status"] as? String ?: "open",
            createdAt = parseTimestampMillis(m["created_at"])
        )
    }

    fun toMarketSummary(m: Map<String, Any?>, fallbackProductId: String): ProductMarketSummary {
        val trendRaw = (m["trend_points"] as? List<*>).orEmpty()
        val points = trendRaw.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val day = parseEpochDay(map["date"])
            val price = (map["avg_price"] as? Number)?.toDouble() ?: return@mapNotNull null
            PriceTrendPoint(day, price)
        }
        return ProductMarketSummary(
            productId = m["product_id"] as? String ?: fallbackProductId,
            unit = m["unit"] as? String ?: "kg",
            averagePrice = (m["average_price"] as? Number)?.toDouble(),
            wholesaleReferencePrice = (m["wholesale_reference_price"] as? Number)?.toDouble(),
            sampleCount = (m["sample_count"] as? Number)?.toInt() ?: 0,
            trendPoints = points
        )
    }
}
