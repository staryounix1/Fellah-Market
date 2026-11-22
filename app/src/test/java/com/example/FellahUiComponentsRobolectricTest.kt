package com.example

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.core.data.ProfileEntity
import com.example.core.data.UserRole
import com.example.core.data.VerificationStatus
import com.example.core.l10n.AppLanguage
import com.example.core.l10n.L10nStrings
import com.example.core.l10n.LocalL10n
import com.example.core.ui.AppUiState
import com.example.core.ui.AuthUiState
import com.example.core.ui.OfflineStatusBanner
import com.example.feature.auth.PhonePinAuthScreen
import com.example.feature.profile.DiagnosticsScreen
import com.example.ui.theme.FellahMarketTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FellahUiComponentsRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `production mode hides demo accounts and signup shows confirm pin`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val l10n = L10nStrings.create(context, AppLanguage.DARIJA)

        composeRule.setContent {
            CompositionLocalProvider(LocalL10n provides l10n) {
                FellahMarketTheme {
                    PhonePinAuthScreen(
                        authState = AuthUiState(phoneInput = "0661112233", isSignUpMode = true),
                        isDemoMode = false,
                        onModeChange = {},
                        onPhoneChange = {},
                        onPinChange = {},
                        onConfirmPinChange = {},
                        onSubmitAuth = {},
                        onQuickDemoLogin = {},
                        onChangeLanguageClick = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("pin_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_pin_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("auth_submit_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("demo_farmer_button").assertDoesNotExist()
        composeRule.onNodeWithTag("demo_buyer_button").assertDoesNotExist()
        composeRule.onNodeWithTag("demo_admin_button").assertDoesNotExist()
    }

    @Test
    fun `diagnostics screen shows masked phone and never exposes full phone or synthetic email`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val l10n = L10nStrings.create(context, AppLanguage.FRENCH)
        val user = ProfileEntity(
            id = "u1",
            phone = "+212661112233",
            fullName = "Lahcen",
            role = UserRole.FARMER,
            verificationStatus = VerificationStatus.VERIFIED,
            regionId = "reg_souss_massa",
            city = "أكادير"
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalL10n provides l10n) {
                FellahMarketTheme {
                    DiagnosticsScreen(
                        state = AppUiState(
                            currentUser = user,
                            isDemoMode = false,
                            isRemoteConfigured = true,
                            hasActiveSession = true,
                            isNetworkConnected = true,
                            pendingSyncCount = 0
                        ),
                        onBack = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("diag_masked_phone").assertTextEquals("+212••••33")
        composeRule.onAllNodesWithText("212661112233", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("fellah.app", substring = true).assertCountEquals(0)
    }

    @Test
    fun `offline status banner displays retry button for failed sync items and fires callback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val l10n = L10nStrings.create(context, AppLanguage.FRENCH)
        var retried = false

        composeRule.setContent {
            CompositionLocalProvider(LocalL10n provides l10n) {
                FellahMarketTheme {
                    OfflineStatusBanner(
                        isOnline = true,
                        pendingSyncCount = 0,
                        onSyncNow = {},
                        failedSyncCount = 2,
                        onRetryFailedSync = { retried = true }
                    )
                }
            }
        }

        composeRule.onNodeWithTag("retry_failed_sync_button").assertIsDisplayed().performClick()
        assertTrue(retried)
    }
}
