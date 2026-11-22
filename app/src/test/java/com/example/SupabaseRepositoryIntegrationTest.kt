package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.auth.*
import com.example.core.data.*
import com.example.core.media.SignedUrlMediaCache
import com.example.core.network.OfferQueryFilter
import com.example.core.network.SupabaseRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SupabaseRepositoryIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: FellahDatabase
    private lateinit var dao: FellahDao
    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryStringStore
    private lateinit var sessionManager: SessionManager
    private lateinit var authClient: SupabaseAuthClient
    private lateinit var repo: SupabaseRepository

    private var fakeClockSec = 1_000L
    private var fakeClockMillis = 1_000_000L
    private var sessionExpiredCallbacks = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, FellahDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
        server = MockWebServer()
        server.start()
        store = InMemoryStringStore()
        fakeClockSec = 1_000L
        fakeClockMillis = 1_000_000L
        sessionExpiredCallbacks = 0

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        sessionManager = SessionManager(store) { fakeClockSec }
        val baseUrl = server.url("/").toString().trimEnd('/')
        authClient = SupabaseAuthClient(
            baseUrlProvider = { baseUrl },
            anonKeyProvider = { "test-anon-key" },
            demoModeProvider = { false },
            sessionManager = sessionManager,
            httpClient = httpClient,
            clockEpochSec = { fakeClockSec }
        )
        repo = SupabaseRepository(
            dao = dao,
            authClient = authClient,
            sessionManager = sessionManager,
            baseUrlProvider = { baseUrl },
            anonKeyProvider = { "test-anon-key" },
            httpClient = httpClient,
            onSessionExpired = { sessionExpiredCallbacks++ },
            clockMillis = { fakeClockMillis }
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun `favorite offers are saved offline per account and can be removed`(): Unit = runBlocking {
        dao.upsertFavoriteOffer(FavoriteOfferEntity(userId = "farmer-1", offerId = "offer-1"))

        assertTrue(dao.isFavoriteOffer("farmer-1", "offer-1"))
        assertFalse(dao.isFavoriteOffer("buyer-2", "offer-1"))

        dao.deleteFavoriteOffer("farmer-1", "offer-1")
        assertFalse(dao.isFavoriteOffer("farmer-1", "offer-1"))
    }

    @Test
    fun `signUpWithPhoneAndPin and loginWithPhoneAndPin use hidden synthetic email and persist encrypted session`(): Unit = runBlocking {
        val sessionBody = """
            {
              "access_token": "jwt-access-1",
              "refresh_token": "refresh-1",
              "expires_in": 3600,
              "user": { "id": "11111111-1111-4111-8111-111111111101", "user_metadata": { "phone": "+212661112233" } }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(sessionBody))

        val signUpRes = authClient.signUpWithPhoneAndPin("0661-11-22-33", "258369", "258369")
        assertTrue(signUpRes.isSuccess)
        val signUpReq = server.takeRequest()
        assertEquals("/auth/v1/signup", signUpReq.path)
        val signUpBody = signUpReq.body.readUtf8()
        assertTrue(signUpBody.contains("212661112233@fellah.app"))
        assertTrue(signUpBody.contains("+212661112233"))

        val session = sessionManager.getSession()
        assertNotNull(session)
        assertEquals("jwt-access-1", session!!.accessToken)
        assertEquals("+212661112233", session.phoneE164)

        // Login with password grant
        server.enqueue(MockResponse().setResponseCode(200).setBody(sessionBody))
        val loginRes = authClient.loginWithPhoneAndPin("0661112233", "258369")
        assertTrue(loginRes.isSuccess)
        val loginReq = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=password", loginReq.path)
        assertTrue(loginReq.body.readUtf8().contains("212661112233@fellah.app"))
    }

    @Test
    fun `authClient maps duplicate signup weak PIN and triggers 15m lockout after 5 failed logins`(): Unit = runBlocking {
        // Weak PIN blocked locally without hitting network
        val weakErr = authClient.signUpWithPhoneAndPin("0661112233", "123456", "123456").exceptionOrNull() as? AuthException
        assertEquals(AuthErrorCode.WEAK_PIN, weakErr?.code)
        assertEquals(0, server.requestCount)

        // Duplicate registration maps to generic PHONE_ALREADY_REGISTERED
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error_code":"user_already_exists","msg":"User already registered"}"""))
        val dupErr = authClient.signUpWithPhoneAndPin("0661112233", "258369", "258369").exceptionOrNull() as? AuthException
        assertEquals(AuthErrorCode.PHONE_ALREADY_REGISTERED, dupErr?.code)
        server.takeRequest()

        // 5 wrong login attempts -> 5th returns ACCOUNT_LOCKED and 6th makes zero network calls
        repeat(4) {
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"Invalid login credentials"}"""))
            val err = authClient.loginWithPhoneAndPin("0661112233", "947261").exceptionOrNull() as? AuthException
            assertEquals(AuthErrorCode.INVALID_CREDENTIALS, err?.code)
            server.takeRequest()
        }
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"Invalid login credentials"}"""))
        val fifthErr = authClient.loginWithPhoneAndPin("0661112233", "947261").exceptionOrNull() as? AuthException
        assertEquals(AuthErrorCode.ACCOUNT_LOCKED, fifthErr?.code)
        assertTrue((fifthErr?.lockoutRemainingSeconds ?: 0) > 800)
        server.takeRequest()

        val reqBefore6th = server.requestCount
        val sixthErr = authClient.loginWithPhoneAndPin("0661112233", "258369").exceptionOrNull() as? AuthException
        assertEquals(AuthErrorCode.ACCOUNT_LOCKED, sixthErr?.code)
        assertEquals(reqBefore6th, server.requestCount)

        // When unconfigured & DEMO_MODE=false, return NOT_CONFIGURED
        val unconfiguredAuth = SupabaseAuthClient(
            baseUrlProvider = { "" },
            anonKeyProvider = { "" },
            demoModeProvider = { false },
            sessionManager = sessionManager
        )
        val noDemo = unconfiguredAuth.loginWithPhoneAndPin("0661112233", "258369").exceptionOrNull() as? AuthException
        assertEquals(AuthErrorCode.NOT_CONFIGURED, noDemo?.code)
    }

    @Test
    fun `changePin verifies current PIN updates user password and clears must_change_pin`(): Unit = runBlocking {
        seedAuthenticatedSession("11111111-1111-4111-8111-111111111101")
        dao.upsertProfile(
            ProfileEntity(
                id = "11111111-1111-4111-8111-111111111101",
                phone = "+212661112233",
                fullName = "Lahcen",
                role = UserRole.FARMER,
                verificationStatus = VerificationStatus.VERIFIED,
                mustChangePin = true,
                regionId = "reg_souss_massa",
                city = "أكادير"
            )
        )

        // Wrong current PIN returns WRONG_CURRENT_PIN
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        val wrongCur = authClient.changePin("741852", "951753", "951753").exceptionOrNull() as? AuthException
        assertEquals(AuthErrorCode.WRONG_CURRENT_PIN, wrongCur?.code)
        server.takeRequest()

        // Valid current PIN -> reauth -> PUT /auth/v1/user -> PATCH /rest/v1/profiles clearing must_change_pin
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"fresh-jwt","refresh_token":"refresh-2","expires_in":3600,"user":{"id":"11111111-1111-4111-8111-111111111101"}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"11111111-1111-4111-8111-111111111101"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"id":"11111111-1111-4111-8111-111111111101","must_change_pin":false}]"""))

        assertTrue(authClient.changePin("258369", "951753", "951753").isSuccess)
        assertTrue(repo.clearMustChangePin("11111111-1111-4111-8111-111111111101").isSuccess)
        assertEquals("/auth/v1/token?grant_type=password", server.takeRequest().path)
        val putReq = server.takeRequest()
        assertEquals("PUT", putReq.method)
        assertEquals("/auth/v1/user", putReq.path)
        val patchReq = server.takeRequest()
        assertEquals("PATCH", patchReq.method)
        assertTrue(patchReq.body.readUtf8().contains(""""must_change_pin":false"""))
        assertFalse(dao.getProfileById("11111111-1111-4111-8111-111111111101")!!.mustChangePin)

        // Admin reset PIN hits Edge Function with Bearer token
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"must_change_pin":true}"""))
        assertTrue(repo.adminResetUserPin("11111111-1111-4111-8111-111111111102", "836291").isSuccess)
        val resetReq = server.takeRequest()
        assertEquals("/functions/v1/admin-reset-pin", resetReq.path)
        assertEquals("Bearer fresh-jwt", resetReq.getHeader("Authorization"))
        assertTrue(resetReq.body.readUtf8().contains("836291"))
    }

    @Test
    fun `getValidAccessToken auto-refreshes expiring session and logout clears session`(): Unit = runBlocking {
        sessionManager.saveSession(
            AuthSession("old-token", "refresh-old", expiresAtEpochSec = 1_030L, userId = "u1", phoneE164 = "+212661112233")
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "access_token": "new-token",
                  "refresh_token": "refresh-new",
                  "expires_in": 3600,
                  "user": { "id": "u1", "phone": "+212661112233" }
                }
                """.trimIndent()
            )
        )
        val tokenRes = authClient.getValidAccessToken()
        assertEquals("new-token", tokenRes.getOrNull())
        val req = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=refresh_token", req.path)
        assertEquals("refresh-new", sessionManager.getSession()?.refreshToken)

        // Logout calls server & clears local store
        server.enqueue(MockResponse().setResponseCode(204))
        authClient.logout()
        assertEquals("/auth/v1/logout", server.takeRequest().path)
        assertNull(sessionManager.getSession())

        // Failed refresh revokes session
        sessionManager.saveSession(
            AuthSession("expired", "bad-refresh", expiresAtEpochSec = 900L, userId = "u1", phoneE164 = "+212661112233")
        )
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_grant"}"""))
        val failedRefresh = authClient.getValidAccessToken()
        assertEquals(AuthErrorCode.SESSION_EXPIRED, (failedRefresh.exceptionOrNull() as? AuthException)?.code)
        assertNull(sessionManager.getSession())
    }

    @Test
    fun `fetchOffersPage sends Range header and server filters and parses Content-Range`(): Unit = runBlocking {
        seedAuthenticatedSession("11111111-1111-4111-8111-111111111101")
        val offerJson = """
            [
              {
                "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1",
                "farmer_id": "11111111-1111-4111-8111-111111111101",
                "product_id": "prod_tomato",
                "category_id": "cat_veg",
                "region_id": "reg_souss_massa",
                "city": "أكادير",
                "quantity": 40,
                "unit": "crate",
                "asking_price": 95,
                "ready_date": "2026-11-22",
                "attributes": {"grade":"A"},
                "status": "active",
                "media_status": "ready",
                "updated_at": "2026-11-22T08:00:00Z",
                "offer_photos": [{"id":"p1","storage_path":"u1/o1_0.jpg","sort_order":0}]
              }
            ]
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "0-0/3")
                .setBody(offerJson)
        )

        val res = repo.fetchOffersPage(
            OfferQueryFilter(
                categoryId = "cat_veg",
                city = "أكادير",
                maxPrice = 120.0,
                page = 0,
                pageSize = 1
            )
        ).getOrThrow()

        val req = server.takeRequest()
        assertEquals("0-0", req.getHeader("Range"))
        assertEquals("count=exact", req.getHeader("Prefer"))
        assertTrue(req.path!!.contains("category_id=eq.cat_veg"))
        assertTrue(req.path!!.contains("asking_price=lte.120.0"))
        assertTrue(res.hasMore)
        assertEquals(3, res.totalCount)
        assertEquals(1, res.items.size)
        assertEquals("u1/o1_0.jpg", res.items.first().photoUrisCsv)
        assertNotNull(dao.getOfferById("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"))
    }

    @Test
    fun `offline queue backoff transitions to FAILED on terminal error and retries cleanly`(): Unit = runBlocking {
        val farmerId = "11111111-1111-4111-8111-111111111101"
        val offerId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1"
        seedAuthenticatedSession(farmerId)

        val localOffer = OfferEntity(
            id = offerId,
            farmerId = farmerId,
            productId = "prod_tomato",
            categoryId = "cat_veg",
            regionId = "reg_souss_massa",
            city = "أكادير",
            quantity = 30.0,
            unit = "crate",
            askingPrice = 110.0,
            readyDateEpochDay = 20700L,
            attributesJson = "{}",
            photoUrisCsv = "drawable:tomatoes"
        )
        // Offline write queues SyncQueueEntity
        repo.upsertOffer(localOffer, isOnline = false)
        assertEquals(1, dao.getPendingSyncQueue().size)
        assertEquals(SyncState.PENDING_SYNC, dao.getOfferById(offerId)?.syncState)

        // LWW check: stale remote response (updatedAt earlier than local 1_000_000L) must NOT overwrite local pending edit
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [
                  {
                    "id": "$offerId",
                    "farmer_id": "$farmerId",
                    "product_id": "prod_tomato",
                    "category_id": "cat_veg",
                    "region_id": "reg_souss_massa",
                    "city": "أكادير",
                    "quantity": 30,
                    "unit": "crate",
                    "asking_price": 75,
                    "status": "active",
                    "updated_at": "1970-01-01T00:05:00Z"
                  }
                ]
                """.trimIndent()
            )
        )
        repo.fetchOffersPage(OfferQueryFilter(page = 0, pageSize = 10))
        server.takeRequest()
        assertEquals(110.0, dao.getOfferById(offerId)!!.askingPrice, 0.01)

        // Transient 500 schedules backoff (+5_000ms)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"upstream down"}"""))
        val firstRun = repo.syncPendingItemsNow()
        server.takeRequest()
        assertEquals(0, firstRun.syncedCount)
        assertEquals(1, firstRun.pendingCount)
        val queuedAfter500 = dao.getPendingSyncQueue().single()
        assertEquals(1, queuedAfter500.retryCount)
        assertEquals(fakeClockMillis + 5_000L, queuedAfter500.nextRetryAt)

        // Advance clock past backoff & hit terminal 400 -> marks FAILED
        fakeClockMillis += 6_000L
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"RLS rejected"}"""))
        val secondRun = repo.syncPendingItemsNow()
        server.takeRequest()
        assertEquals(1, secondRun.failedCount)
        assertEquals(0, secondRun.pendingCount)
        assertEquals(SyncState.FAILED, dao.getFailedSyncQueue().single().status)

        // Manual retry resets FAILED items & succeeds on 200
        server.enqueue(MockResponse().setResponseCode(201).setBody("[]"))
        val retryRun = repo.retryFailedSyncItems()
        server.takeRequest()
        assertEquals(1, retryRun.syncedCount)
        assertEquals(0, retryRun.failedCount)
        assertEquals(SyncState.SYNCED, dao.getOfferById(offerId)?.syncState)
    }

    @Test
    fun `performDealAction RPC propagates trigger errors and fetchMarketPriceSummary parses RPC jsonb`(): Unit = runBlocking {
        val buyerId = "11111111-1111-4111-8111-111111111102"
        val farmerId = "11111111-1111-4111-8111-111111111101"
        val dealId = "cccccccc-cccc-4ccc-8ccc-ccccccccccc1"
        seedAuthenticatedSession(buyerId)

        val deal = DealEntity(
            id = dealId,
            offerId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1",
            buyRequestId = null,
            farmerId = farmerId,
            buyerId = buyerId,
            productId = "prod_tomato",
            quantity = 20.0,
            unit = "crate",
            proposedPrice = 85.0,
            deliveryType = "pickup",
            preferredDateEpochDay = 20700L,
            status = DealStatus.REQUESTED,
            lastActorRole = UserRole.BUYER
        )
        dao.upsertDeal(deal)

        // Trigger error from Postgres (400) surfaces cleanly and does not enqueue retry
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Not your turn to act on this negotiation"}"""))
        val errRes = repo.performDealAction(deal, "accept", null, "", buyerId, UserRole.BUYER, isOnline = true)
        server.takeRequest()
        assertTrue(errRes.isFailure)
        assertEquals("Not your turn to act on this negotiation", errRes.exceptionOrNull()?.message)
        assertEquals(0, dao.getPendingSyncQueue().size)

        // Valid RPC response + history + unmasked phone refresh on accept
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "$dealId",
                  "farmer_id": "$farmerId",
                  "buyer_id": "$buyerId",
                  "product_id": "prod_tomato",
                  "quantity": 20,
                  "unit": "crate",
                  "proposed_price": 85,
                  "agreed_price": 85,
                  "delivery_type": "pickup",
                  "preferred_date": "2026-11-22",
                  "status": "accepted",
                  "last_actor_role": "farmer",
                  "updated_at": "2026-11-22T09:00:00Z"
                }
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"id":"$farmerId","full_name":"Lahcen","role":"farmer","verification_status":"verified","region_id":"reg_souss_massa","city":"أكادير","phone":"+212661112233"}]"""
            )
        )

        val okRes = repo.performDealAction(deal, "accept", null, "OK", farmerId, UserRole.FARMER, isOnline = true)
        assertTrue(okRes.isSuccess)
        assertEquals(DealStatus.ACCEPTED, okRes.getOrThrow().status)
        assertEquals("+212661112233", dao.getProfileById(farmerId)?.phone)

        // Market Price Summary RPC
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "product_id": "prod_tomato",
                  "unit": "crate",
                  "average_price": 91.5,
                  "wholesale_reference_price": 88.0,
                  "sample_count": 7,
                  "trend_points": [
                    {"date": "2026-11-20", "avg_price": 86.0},
                    {"date": "2026-11-22", "avg_price": 90.0}
                  ]
                }
                """.trimIndent()
            )
        )
        val summary = repo.fetchMarketPriceSummary("prod_tomato", "reg_souss_massa", "crate")
        assertEquals(91.5, summary.averagePrice!!, 0.01)
        assertEquals(88.0, summary.wholesaleReferencePrice!!, 0.01)
        assertEquals(7, summary.sampleCount)
        assertEquals(2, summary.trendPoints.size)
    }

    @Test
    fun `SignedUrlMediaCache signs object URL and caches downloaded bytes on disk`(): Unit = runBlocking {
        seedAuthenticatedSession("u1")
        val baseUrl = server.url("/").toString().trimEnd('/')
        val mediaCache = SignedUrlMediaCache(
            context = context,
            authClient = authClient,
            baseUrlProvider = { baseUrl },
            anonKeyProvider = { "test-anon-key" }
        )

        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"signedURL":"/object/sign/offer-photos/u1/o1_0.jpg?token=sig123"}""")
        )
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x42, 0x43)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(okio.Buffer().write(fakeJpeg))
        )

        val cachedFile = mediaCache.resolveLocalOrCachedFile("offer-photos", "u1/o1_0.jpg")
        assertNotNull(cachedFile)
        assertTrue(cachedFile!!.exists())
        assertArrayEquals(fakeJpeg, cachedFile.readBytes())
        assertEquals(2, server.requestCount)

        // Second lookup returns disk cached file without network call
        val secondLookup = mediaCache.resolveLocalOrCachedFile("offer-photos", "u1/o1_0.jpg")
        assertEquals(cachedFile.absolutePath, secondLookup?.absolutePath)
        assertEquals(2, server.requestCount)

        // Clean up test cache file
        File(context.cacheDir, "remote_media").deleteRecursively()
    }

    private suspend fun seedAuthenticatedSession(userId: String) {
        sessionManager.saveSession(
            AuthSession(
                accessToken = "valid-jwt",
                refreshToken = "valid-refresh",
                expiresAtEpochSec = 99_999L,
                userId = userId,
                phoneE164 = "+212661112233"
            )
        )
    }
}
