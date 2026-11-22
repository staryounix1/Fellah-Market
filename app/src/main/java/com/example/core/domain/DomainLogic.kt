package com.example.core.domain

import com.example.core.data.DealEntity
import com.example.core.data.DealStatus
import com.example.core.data.OfferEntity
import com.example.core.data.OfferStatus
import com.example.core.data.ReferencePriceEntity
import com.example.core.data.UserRole
import com.example.core.l10n.AppLanguage
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlin.math.abs

enum class AttributeFieldType {
    SELECT,
    NUMBER,
    BOOLEAN,
    TEXT
}

data class DynamicFormField(
    val key: String,
    val labelDarija: String,
    val labelAr: String,
    val labelFr: String,
    val type: AttributeFieldType,
    val required: Boolean = false,
    val options: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null
) {
    fun label(language: AppLanguage): String = when (language) {
        AppLanguage.DARIJA -> labelDarija
        AppLanguage.ARABIC -> labelAr
        AppLanguage.FRENCH -> labelFr
    }
}

data class ValidationResult(
    val isValid: Boolean,
    val fieldErrors: Map<String, String> = emptyMap()
)

object DynamicFormSchemaCodec {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val listMapType = Types.newParameterizedType(List::class.java, Map::class.java)
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)

    fun parseSchema(schemaJson: String): List<DynamicFormField> {
        if (schemaJson.isBlank()) return emptyList()
        return runCatching {
            val adapter = moshi.adapter<List<Map<String, Any?>>>(listMapType)
            val rows = adapter.fromJson(schemaJson).orEmpty()
            rows.mapNotNull { obj ->
                val key = obj["key"] as? String ?: return@mapNotNull null
                val typeStr = (obj["type"] as? String ?: "text").lowercase()
                val type = when (typeStr) {
                    "select" -> AttributeFieldType.SELECT
                    "number" -> AttributeFieldType.NUMBER
                    "boolean" -> AttributeFieldType.BOOLEAN
                    else -> AttributeFieldType.TEXT
                }
                val opts = (obj["options"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
                val min = (obj["min"] as? Number)?.toDouble()
                val max = (obj["max"] as? Number)?.toDouble()
                DynamicFormField(
                    key = key,
                    labelDarija = obj["labelDarija"] as? String ?: key,
                    labelAr = obj["labelAr"] as? String ?: key,
                    labelFr = obj["labelFr"] as? String ?: key,
                    type = type,
                    required = obj["required"] as? Boolean ?: false,
                    options = opts,
                    min = min,
                    max = max
                )
            }
        }.getOrDefault(emptyList())
    }

    fun parseValues(attributesJson: String): Map<String, String> {
        if (attributesJson.isBlank()) return emptyMap()
        return runCatching {
            val adapter = moshi.adapter<Map<String, Any?>>(mapType)
            adapter.fromJson(attributesJson).orEmpty()
                .mapValues { it.value?.toString().orEmpty() }
        }.getOrDefault(emptyMap())
    }

    fun encodeValues(values: Map<String, String>): String {
        val adapter = moshi.adapter<Map<String, String>>(
            Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        )
        return adapter.toJson(values.filterValues { it.isNotBlank() })
    }

    fun validate(
        schema: List<DynamicFormField>,
        values: Map<String, String>,
        quantity: Double?,
        askingPrice: Double?
    ): ValidationResult {
        val errors = mutableMapOf<String, String>()
        if (quantity == null || quantity <= 0.0) {
            errors["quantity"] = "invalid_quantity"
        }
        if (askingPrice == null || askingPrice <= 0.0) {
            errors["askingPrice"] = "invalid_price"
        }
        for (field in schema) {
            val raw = values[field.key]?.trim().orEmpty()
            if (field.required && raw.isEmpty()) {
                errors[field.key] = "required"
                continue
            }
            if (raw.isNotEmpty()) {
                when (field.type) {
                    AttributeFieldType.SELECT -> {
                        if (field.options.isNotEmpty() && raw !in field.options) {
                            errors[field.key] = "invalid_option"
                        }
                    }
                    AttributeFieldType.NUMBER -> {
                        val num = raw.replace(',', '.').toDoubleOrNull()
                        if (num == null) {
                            errors[field.key] = "invalid_number"
                        } else {
                            if (field.min != null && num < field.min) {
                                errors[field.key] = "below_min"
                            }
                            if (field.max != null && num > field.max) {
                                errors[field.key] = "above_max"
                            }
                        }
                    }
                    AttributeFieldType.BOOLEAN -> {
                        if (raw != "true" && raw != "false") {
                            errors[field.key] = "invalid_boolean"
                        }
                    }
                    AttributeFieldType.TEXT -> Unit
                }
            }
        }
        return ValidationResult(isValid = errors.isEmpty(), fieldErrors = errors)
    }
}

object DealStateMachine {
    fun canTransition(currentStatus: String, nextStatus: String): Boolean = when (currentStatus) {
        DealStatus.REQUESTED -> nextStatus in setOf(
            DealStatus.COUNTERED,
            DealStatus.ACCEPTED,
            DealStatus.CANCELLED
        )
        DealStatus.COUNTERED -> nextStatus in setOf(
            DealStatus.COUNTERED,
            DealStatus.ACCEPTED,
            DealStatus.CANCELLED
        )
        DealStatus.ACCEPTED -> nextStatus in setOf(
            DealStatus.COMPLETED,
            DealStatus.CANCELLED
        )
        DealStatus.COMPLETED, DealStatus.CANCELLED -> false
        else -> false
    }

    fun canActOnNegotiation(deal: DealEntity, actorRole: String): Boolean {
        if (deal.status !in setOf(DealStatus.REQUESTED, DealStatus.COUNTERED)) return false
        return deal.lastActorRole != actorRole
    }

    fun applyConfirmation(deal: DealEntity, actorRole: String): DealEntity {
        if (deal.status != DealStatus.ACCEPTED) return deal
        val farmerConfirmed = deal.farmerConfirmedCompletion || actorRole == UserRole.FARMER
        val buyerConfirmed = deal.buyerConfirmedCompletion || actorRole == UserRole.BUYER
        val bothConfirmed = farmerConfirmed && buyerConfirmed
        return deal.copy(
            farmerConfirmedCompletion = farmerConfirmed,
            buyerConfirmedCompletion = buyerConfirmed,
            status = if (bothConfirmed) DealStatus.COMPLETED else DealStatus.ACCEPTED,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun isPhoneRevealed(dealStatus: String): Boolean =
        dealStatus == DealStatus.ACCEPTED || dealStatus == DealStatus.COMPLETED
}

enum class PriceHintLevel {
    NONE,
    BELOW_MARKET,
    FAIR_MARKET,
    ABOVE_MARKET
}

data class PriceTrendPoint(
    val epochDay: Long,
    val avgPrice: Double
)

data class ProductMarketSummary(
    val productId: String,
    val unit: String,
    val averagePrice: Double?,
    val wholesaleReferencePrice: Double?,
    val sampleCount: Int,
    val trendPoints: List<PriceTrendPoint>
)

object PriceAnalytics {
    fun computeMarketSummary(
        productId: String,
        regionId: String?,
        deals: List<DealEntity>,
        offers: List<OfferEntity>,
        referencePrices: List<ReferencePriceEntity>,
        todayEpochDay: Long,
        preferredUnit: String? = null
    ): ProductMarketSummary {
        val completedDeals = deals.filter {
            it.productId == productId &&
                it.status == DealStatus.COMPLETED &&
                (it.agreedPrice ?: it.proposedPrice) > 0 &&
                (preferredUnit == null || it.unit == preferredUnit)
        }
        val activeOffers = offers.filter {
            it.productId == productId &&
                (regionId == null || it.regionId == regionId) &&
                it.status in setOf(OfferStatus.ACTIVE, OfferStatus.RESERVED) &&
                it.askingPrice > 0 &&
                (preferredUnit == null || it.unit == preferredUnit)
        }
        val refs = referencePrices.filter {
            it.productId == productId &&
                (regionId == null || it.regionId == regionId) &&
                (preferredUnit == null || it.unit == preferredUnit)
        }.sortedBy { it.recordedEpochDay }

        val unit = preferredUnit
            ?: completedDeals.firstOrNull()?.unit
            ?: activeOffers.firstOrNull()?.unit
            ?: refs.firstOrNull()?.unit
            ?: "kg"

        // Weighted average: completed deals weighted 1.5x, active offers weighted 1.0x
        var weightedSum = 0.0
        var totalWeight = 0.0
        completedDeals.forEach {
            val price = it.agreedPrice ?: it.proposedPrice
            weightedSum += price * 1.5
            totalWeight += 1.5
        }
        activeOffers.forEach {
            weightedSum += it.askingPrice
            totalWeight += 1.0
        }

        val sampleCount = completedDeals.size + activeOffers.size
        val latestRef = refs.lastOrNull()?.priceMad
        val avgPrice = if (totalWeight > 0.0) weightedSum / totalWeight else latestRef

        // Build 30-day trend from reference prices + recent deals/offers
        val windowStart = todayEpochDay - 29
        val pointsByDay = mutableMapOf<Long, MutableList<Double>>()
        refs.filter { it.recordedEpochDay >= windowStart }.forEach {
            pointsByDay.getOrPut(it.recordedEpochDay) { mutableListOf() }.add(it.priceMad)
        }
        completedDeals.forEach { d ->
            val day = d.updatedAt / 86_400_000L
            if (day >= windowStart) {
                pointsByDay.getOrPut(day) { mutableListOf() }.add(d.agreedPrice ?: d.proposedPrice)
            }
        }
        val trend = pointsByDay.entries
            .sortedBy { it.key }
            .map { (day, prices) -> PriceTrendPoint(day, prices.average()) }

        return ProductMarketSummary(
            productId = productId,
            unit = unit,
            averagePrice = avgPrice,
            wholesaleReferencePrice = latestRef,
            sampleCount = sampleCount + refs.size,
            trendPoints = trend
        )
    }

    fun evaluateAskingPriceHint(
        askingPrice: Double?,
        marketAverage: Double?,
        sampleCount: Int
    ): PriceHintLevel {
        if (askingPrice == null || askingPrice <= 0.0 || marketAverage == null || marketAverage <= 0.0) {
            return PriceHintLevel.NONE
        }
        if (sampleCount < 2) return PriceHintLevel.NONE
        val deltaRatio = (askingPrice - marketAverage) / marketAverage
        return when {
            deltaRatio > 0.25 -> PriceHintLevel.ABOVE_MARKET
            deltaRatio < -0.25 -> PriceHintLevel.BELOW_MARKET
            abs(deltaRatio) <= 0.25 -> PriceHintLevel.FAIR_MARKET
            else -> PriceHintLevel.NONE
        }
    }
}

object PhoneValidator {
    fun normalizeMoroccanPhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.startsWith("212") && digits.length == 12 -> "0" + digits.substring(3)
            digits.startsWith("00212") && digits.length == 14 -> "0" + digits.substring(5)
            else -> digits
        }
    }

    fun toE164Moroccan(raw: String): String {
        val local = normalizeMoroccanPhone(raw)
        return when {
            local.startsWith("0") && local.length == 10 -> "+212" + local.substring(1)
            local.startsWith("212") && local.length == 12 -> "+$local"
            raw.trim().startsWith("+212") -> "+212" + raw.filter { it.isDigit() }.removePrefix("212")
            else -> "+212" + local.trimStart('0')
        }
    }

    /**
     * Single canonical helper mapping a Moroccan phone number to its hidden synthetic Supabase Auth email.
     * Never display this value in the UI or write it to logs.
     */
    fun toSyntheticEmail(raw: String): String {
        val digits = toE164Moroccan(raw).filter { it.isDigit() }
        return "$digits@fellah.app"
    }

    /**
     * Masks phone numbers so full numbers are never exposed on the Diagnostics screen.
     */
    fun maskPhoneForDiagnostics(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty()) return "—"
        if (clean.length <= 4) return "••••"
        return "${clean.take(4)}••••${clean.takeLast(2)}"
    }

    fun isValidMoroccanPhone(raw: String): Boolean {
        val norm = normalizeMoroccanPhone(raw)
        return norm.length == 10 && (norm.startsWith("06") || norm.startsWith("07") || norm.startsWith("05"))
    }
}

enum class PinValidationError {
    TOO_SHORT,
    NON_DIGITS,
    WEAK_REPEATED,
    WEAK_SEQUENCE,
    MISMATCH
}

object PinValidator {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 12

    private val blockedSequences = setOf(
        "012345", "123456", "234567", "345678", "456789",
        "987654", "876543", "765432", "654321", "543210"
    )

    fun validate(pin: String): PinValidationError? {
        if (pin.any { !it.isDigit() }) return PinValidationError.NON_DIGITS
        if (pin.length < MIN_LENGTH || pin.length > MAX_LENGTH) return PinValidationError.TOO_SHORT
        if (pin.toSet().size <= 1) return PinValidationError.WEAK_REPEATED
        if (pin.length % 2 == 0 && pin.chunked(2).toSet().size == 1) return PinValidationError.WEAK_REPEATED

        val diffs = (1 until pin.length).map { (pin[it] - pin[it - 1]) }
        if (diffs.all { it == 1 } || diffs.all { it == -1 }) {
            return PinValidationError.WEAK_SEQUENCE
        }
        if (blockedSequences.any { pin.contains(it) }) {
            return PinValidationError.WEAK_SEQUENCE
        }
        return null
    }

    fun validateWithConfirmation(pin: String, confirmPin: String): PinValidationError? {
        validate(pin)?.let { return it }
        if (pin != confirmPin) return PinValidationError.MISMATCH
        return null
    }
}

data class LockoutState(
    val failedAttempts: Int = 0,
    val lockoutUntilMillis: Long = 0L
)

interface LockoutStore {
    suspend fun read(): LockoutState
    suspend fun write(state: LockoutState)
    suspend fun clear()
}

class InMemoryLockoutStore : LockoutStore {
    private var state = LockoutState()
    override suspend fun read(): LockoutState = state
    override suspend fun write(state: LockoutState) {
        this.state = state
    }
    override suspend fun clear() {
        this.state = LockoutState()
    }
}

class PinLockoutManager(
    private val store: LockoutStore,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun remainingLockoutSeconds(): Int {
        val current = store.read()
        val now = clockMillis()
        if (current.lockoutUntilMillis <= 0L) return 0
        if (now >= current.lockoutUntilMillis) {
            store.clear()
            return 0
        }
        return (((current.lockoutUntilMillis - now) + 999L) / 1000L).toInt().coerceAtLeast(1)
    }

    suspend fun currentFailedAttempts(): Int {
        val current = store.read()
        val now = clockMillis()
        if (current.lockoutUntilMillis > 0L && now >= current.lockoutUntilMillis) {
            store.clear()
            return 0
        }
        return current.failedAttempts
    }

    suspend fun recordFailure(): LockoutState {
        val now = clockMillis()
        val prev = store.read()
        val baseCount = if (prev.lockoutUntilMillis > 0L && now >= prev.lockoutUntilMillis) 0 else prev.failedAttempts
        val nextCount = baseCount + 1
        val nextUntil = if (nextCount >= MAX_ATTEMPTS) now + LOCKOUT_DURATION_MS else 0L
        val next = LockoutState(failedAttempts = nextCount, lockoutUntilMillis = nextUntil)
        store.write(next)
        return next
    }

    suspend fun recordSuccess() {
        store.clear()
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_DURATION_MS = 15L * 60L * 1000L
    }
}
