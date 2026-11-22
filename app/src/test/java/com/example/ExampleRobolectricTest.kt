package com.example

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `app name matches Fellah-Market`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Fellah-Market", appName)
    }

    @Test
    fun `onboarding language selection and demo farmer login flow`() {
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("lang_option_ary").fetchSemanticsNodes().isNotEmpty()
        }

        // Language selection screen shows Darija and French options
        composeRule.onNodeWithTag("lang_option_ary").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("lang_option_fr").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("language_continue_button").performScrollTo().performClick()

        // Wait for DataStore to emit hasSelectedLanguage = true -> Phone + PIN screen appears
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("phone_input").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("phone_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("pin_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("auth_submit_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("demo_farmer_button").performScrollTo().performClick()

        // Wait for Room seed + Farmer home screen
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("create_offer_fab").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("create_offer_fab").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_deals").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_prices").performClick()
        composeRule.waitForIdle()
    }
}
