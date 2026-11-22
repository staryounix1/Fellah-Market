package com.example

import com.example.core.data.*
import com.example.core.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FellahDomainUnitTests {

    @Test
    fun `deal state machine transitions and dual completion work correctly`() {
        assertTrue(DealStateMachine.canTransition(DealStatus.REQUESTED, DealStatus.COUNTERED))
        assertTrue(DealStateMachine.canTransition(DealStatus.COUNTERED, DealStatus.ACCEPTED))
        assertTrue(DealStateMachine.canTransition(DealStatus.ACCEPTED, DealStatus.COMPLETED))
        assertFalse(DealStateMachine.canTransition(DealStatus.COMPLETED, DealStatus.CANCELLED))
        assertFalse(DealStateMachine.canTransition(DealStatus.CANCELLED, DealStatus.ACCEPTED))

        val requested = DealEntity(
            id = "d1", offerId = "o1", buyRequestId = null,
            farmerId = "f1", buyerId = "b1", productId = "p1",
            quantity = 20.0, unit = "crate", proposedPrice = 80.0,
            deliveryType = "pickup", preferredDateEpochDay = 20000L,
            status = DealStatus.REQUESTED, lastActorRole = UserRole.BUYER
        )
        // Buyer sent request, so farmer acts next (not buyer)
        assertTrue(DealStateMachine.canActOnNegotiation(requested, UserRole.FARMER))
        assertFalse(DealStateMachine.canActOnNegotiation(requested, UserRole.BUYER))
        assertFalse(DealStateMachine.isPhoneRevealed(requested.status))

        val accepted = requested.copy(status = DealStatus.ACCEPTED, agreedPrice = 80.0)
        assertTrue(DealStateMachine.isPhoneRevealed(accepted.status))

        // First confirmation by farmer keeps status ACCEPTED
        val afterFarmer = DealStateMachine.applyConfirmation(accepted, UserRole.FARMER)
        assertTrue(afterFarmer.farmerConfirmedCompletion)
        assertFalse(afterFarmer.buyerConfirmedCompletion)
        assertEquals(DealStatus.ACCEPTED, afterFarmer.status)

        // Second confirmation by buyer transitions to COMPLETED
        val afterBoth = DealStateMachine.applyConfirmation(afterFarmer, UserRole.BUYER)
        assertTrue(afterBoth.farmerConfirmedCompletion)
        assertTrue(afterBoth.buyerConfirmedCompletion)
        assertEquals(DealStatus.COMPLETED, afterBoth.status)
    }

    @Test
    fun `price analytics computes weighted average and market hints`() {
        val today = LocalDate.now().toEpochDay()
        val deals = listOf(
            DealEntity(
                id = "d1", offerId = "o1", buyRequestId = null,
                farmerId = "f1", buyerId = "b1", productId = "prod_tomato",
                quantity = 50.0, unit = "crate", proposedPrice = 80.0, agreedPrice = 80.0,
                deliveryType = "pickup", preferredDateEpochDay = today,
                status = DealStatus.COMPLETED, updatedAt = System.currentTimeMillis()
            )
        )
        val offers = listOf(
            OfferEntity(
                id = "o2", farmerId = "f1", productId = "prod_tomato", categoryId = "cat_veg",
                regionId = "reg_souss_massa", city = "أكادير", quantity = 40.0, unit = "crate",
                askingPrice = 100.0, readyDateEpochDay = today, attributesJson = "{}",
                photoUrisCsv = "drawable:tomatoes", status = OfferStatus.ACTIVE
            )
        )
        val refs = listOf(
            ReferencePriceEntity("r1", "prod_tomato", "reg_souss_massa", "crate", 85.0, today - 2),
            ReferencePriceEntity("r2", "prod_tomato", "reg_souss_massa", "crate", 90.0, today)
        )

        val summary = PriceAnalytics.computeMarketSummary(
            productId = "prod_tomato",
            regionId = "reg_souss_massa",
            deals = deals,
            offers = offers,
            referencePrices = refs,
            todayEpochDay = today,
            preferredUnit = "crate"
        )

        // (80 * 1.5 + 100 * 1.0) / 2.5 = 220 / 2.5 = 88.0
        assertNotNull(summary.averagePrice)
        assertEquals(88.0, summary.averagePrice!!, 0.01)
        assertEquals(90.0, summary.wholesaleReferencePrice!!, 0.01)
        assertEquals(2, summary.trendPoints.size)

        // Hints
        assertEquals(PriceHintLevel.ABOVE_MARKET, PriceAnalytics.evaluateAskingPriceHint(130.0, 88.0, 4))
        assertEquals(PriceHintLevel.BELOW_MARKET, PriceAnalytics.evaluateAskingPriceHint(50.0, 88.0, 4))
        assertEquals(PriceHintLevel.FAIR_MARKET, PriceAnalytics.evaluateAskingPriceHint(92.0, 88.0, 4))
        assertEquals(PriceHintLevel.NONE, PriceAnalytics.evaluateAskingPriceHint(130.0, 88.0, 1))
    }

    @Test
    fun `dynamic json schema validation checks required fields and numeric bounds`() {
        val schemaJson = """
            [
              {"key":"variety","labelDarija":"النوع","labelAr":"الصنف","labelFr":"Variété","type":"select","required":true,"options":["بيشولين","منارة"]},
              {"key":"acidity","labelDarija":"الحموضة","labelAr":"الحموضة","labelFr":"Acidité","type":"number","required":true,"min":0.1,"max":3.5}
            ]
        """.trimIndent()
        val schema = DynamicFormSchemaCodec.parseSchema(schemaJson)
        assertEquals(2, schema.size)

        // Missing required & out-of-range acidity
        val invalid = DynamicFormSchemaCodec.validate(
            schema = schema,
            values = mapOf("acidity" to "4.2"),
            quantity = 100.0,
            askingPrice = 75.0
        )
        assertFalse(invalid.isValid)
        assertEquals("required", invalid.fieldErrors["variety"])
        assertEquals("above_max", invalid.fieldErrors["acidity"])

        // Valid values
        val valid = DynamicFormSchemaCodec.validate(
            schema = schema,
            values = mapOf("variety" to "بيشولين", "acidity" to "0.8"),
            quantity = 100.0,
            askingPrice = 75.0
        )
        assertTrue(valid.isValid)
    }

    @Test
    fun `moroccan phone validator normalizes +212 maps synthetic email and masks phone`() {
        assertTrue(PhoneValidator.isValidMoroccanPhone("0661112233"))
        assertTrue(PhoneValidator.isValidMoroccanPhone("+212 661-112-233"))
        assertEquals("0661112233", PhoneValidator.normalizeMoroccanPhone("+212661112233"))
        assertEquals("+212661112233", PhoneValidator.toE164Moroccan("0661-11-22-33"))
        assertEquals("+212661112233", PhoneValidator.toE164Moroccan("+212 661112233"))
        assertEquals("212661112233@fellah.app", PhoneValidator.toSyntheticEmail("0661-11-22-33"))
        assertEquals("212661112233@fellah.app", PhoneValidator.toSyntheticEmail("+212 661112233"))
        assertEquals("+212••••33", PhoneValidator.maskPhoneForDiagnostics("+212661112233"))
        assertEquals("0661••••33", PhoneValidator.maskPhoneForDiagnostics("0661112233"))
        assertEquals("—", PhoneValidator.maskPhoneForDiagnostics(""))
        assertFalse(PhoneValidator.isValidMoroccanPhone("12345"))
    }

    @Test
    fun `pin validator rejects weak PINs mismatches and accepts strong PINs`() {
        assertEquals(PinValidationError.TOO_SHORT, PinValidator.validate("12345"))
        assertEquals(PinValidationError.NON_DIGITS, PinValidator.validate("12ab56"))
        assertEquals(PinValidationError.WEAK_REPEATED, PinValidator.validate("111111"))
        assertEquals(PinValidationError.WEAK_REPEATED, PinValidator.validate("000000"))
        assertEquals(PinValidationError.WEAK_REPEATED, PinValidator.validate("121212"))
        assertEquals(PinValidationError.WEAK_SEQUENCE, PinValidator.validate("123456"))
        assertEquals(PinValidationError.WEAK_SEQUENCE, PinValidator.validate("654321"))
        assertEquals(PinValidationError.WEAK_SEQUENCE, PinValidator.validate("987654"))
        assertEquals(PinValidationError.MISMATCH, PinValidator.validateWithConfirmation("258369", "258368"))
        assertNull(PinValidator.validateWithConfirmation("258369", "258369"))
        assertNull(PinValidator.validate("84729104"))
    }

    @Test
    fun `pin lockout manager locks after 5 failures for 15 minutes and unlocks on expiry or success`() = kotlinx.coroutines.runBlocking {
        var now = 1_000_000L
        val store = InMemoryLockoutStore()
        val manager = PinLockoutManager(store) { now }

        repeat(4) { idx ->
            val st = manager.recordFailure()
            assertEquals(idx + 1, st.failedAttempts)
            assertEquals(0L, st.lockoutUntilMillis)
            assertEquals(0, manager.remainingLockoutSeconds())
        }

        // 5th failure activates 15-minute lockout
        val locked = manager.recordFailure()
        assertEquals(5, locked.failedAttempts)
        assertEquals(now + 15L * 60L * 1000L, locked.lockoutUntilMillis)
        assertEquals(900, manager.remainingLockoutSeconds())

        // Advance 10 minutes -> still locked (300s left)
        now += 10L * 60L * 1000L
        assertEquals(300, manager.remainingLockoutSeconds())

        // Advance past 15 minutes -> unlocked & store cleared
        now += 5L * 60L * 1000L + 1L
        assertEquals(0, manager.remainingLockoutSeconds())
        assertEquals(0, manager.currentFailedAttempts())

        // Success resets attempts
        manager.recordFailure()
        assertEquals(1, manager.currentFailedAttempts())
        manager.recordSuccess()
        assertEquals(0, manager.currentFailedAttempts())
    }

    @Test
    fun `sync queue exponential backoff progresses up to cap`() {
        assertEquals(5_000L, com.example.core.network.SupabaseRepository.computeBackoffMillis(1))
        assertEquals(10_000L, com.example.core.network.SupabaseRepository.computeBackoffMillis(2))
        assertEquals(20_000L, com.example.core.network.SupabaseRepository.computeBackoffMillis(3))
        assertEquals(40_000L, com.example.core.network.SupabaseRepository.computeBackoffMillis(4))
        assertEquals(80_000L, com.example.core.network.SupabaseRepository.computeBackoffMillis(5))
    }
}
