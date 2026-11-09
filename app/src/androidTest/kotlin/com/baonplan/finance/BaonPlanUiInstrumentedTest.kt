package com.baonplan.finance

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baonplan.finance.domain.model.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class BaonPlanUiInstrumentedTest {

  private val resetStateRule = TestRule { base, _ ->
    object : Statement() {
      override fun evaluate() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BaonPlanApp
        runBlocking {
          app.repository.clearAllData()
          app.preferencesDataStore.setThemeMode(ThemeMode.SYSTEM)
        }
        try {
          base.evaluate()
        } finally {
          runBlocking {
            app.repository.clearAllData()
            app.preferencesDataStore.setThemeMode(ThemeMode.SYSTEM)
          }
        }
      }
    }
  }

  private val composeTestRule = createAndroidComposeRule<MainActivity>()

  @get:Rule
  val ruleChain: RuleChain = RuleChain.outerRule(resetStateRule).around(composeTestRule)

  private fun scrollLazyListTo(text: String) {
    composeTestRule
      .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
      .onFirst()
      .performScrollToNode(hasText(text, substring = true))
  }

  @Test
  fun cleanStartNavigatesAll4PrimaryTabsAndSecondaryScreens() {
    composeTestRule.waitUntil(timeoutMillis = 8_000) {
      runCatching {
        composeTestRule.onNodeWithText("Here's your money overview.").assertIsDisplayed()
        true
      }.getOrDefault(false)
    }

    // Home screen core elements & clean start
    composeTestRule.onAllNodesWithText("₱0.00").onFirst().assertIsDisplayed()
    composeTestRule.onNodeWithText("Add expense").assertIsDisplayed()
    composeTestRule.onNodeWithText("Record payment").assertIsDisplayed()

    // Navigate to Budget tab
    composeTestRule.onNodeWithText("Budget").performClick()
    composeTestRule.onNodeWithText("How much you can safely spend this week").assertIsDisplayed()
    scrollLazyListTo("Food budget")
    composeTestRule.onNodeWithText("Food budget").assertIsDisplayed()
    scrollLazyListTo("Weekly scenarios & simulator")
    composeTestRule.onNodeWithText("Weekly scenarios & simulator").assertIsDisplayed()

    // Navigate to Debts tab
    composeTestRule.onNodeWithText("Debts").performClick()
    composeTestRule.onNodeWithText("Your debts").assertIsDisplayed()
    composeTestRule.onNodeWithText("No active debts").assertIsDisplayed()

    // Navigate to More tab
    composeTestRule.onNodeWithText("More").performClick()
    composeTestRule.onNodeWithText("Payment calendar").assertIsDisplayed()
    composeTestRule.onNodeWithText("Transaction history").assertIsDisplayed()
    composeTestRule.onNodeWithText("Appearance").assertIsDisplayed()

    // Open Payment calendar -> defaults to Upcoming, bottom bar hidden
    composeTestRule.onNodeWithText("Payment calendar").performClick()
    composeTestRule.onNodeWithText("Home").assertDoesNotExist()
    composeTestRule.onNodeWithText("Upcoming").assertIsDisplayed()
    composeTestRule.onNodeWithText("Calendar").performClick()
    composeTestRule.onNodeWithText("Mon").assertIsDisplayed()
    composeTestRule.onNodeWithContentDescription("Back").performClick()
    composeTestRule.onNodeWithText("Home").assertIsDisplayed()

    // Open Transaction history & Reports
    composeTestRule.onNodeWithText("Transaction history").performClick()
    composeTestRule.onNodeWithText("No matching transactions").assertIsDisplayed()
    composeTestRule.onNodeWithContentDescription("Back").performClick()

    composeTestRule.onNodeWithText("Reports").performClick()
    composeTestRule.onNodeWithText("Debt breakdown").assertIsDisplayed()
    composeTestRule.onNodeWithContentDescription("Back").performClick()

    // Open Appearance and test Dark/Light switch
    composeTestRule.onNodeWithText("Appearance").performClick()
    composeTestRule.onNodeWithText("Dark mode").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Light mode").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("System default").performClick()
    composeTestRule.onNodeWithContentDescription("Back").performClick()

    // Return Home
    composeTestRule.onNodeWithText("Home").performClick()
    composeTestRule.onNodeWithText("Here's your money overview.").assertIsDisplayed()
  }

  @Test
  fun recordAllowanceExpenseDebtAndPaymentFlowUpdatesAndClearsCleanly() {
    composeTestRule.waitUntil(timeoutMillis = 8_000) {
      runCatching {
        composeTestRule.onNodeWithText("Here's your money overview.").assertIsDisplayed()
        true
      }.getOrDefault(false)
    }

    // 1. Add Debt via Debts tab
    composeTestRule.onNodeWithText("Debts").performClick()
    composeTestRule.onAllNodesWithText("Add debt").onFirst().performClick()
    composeTestRule.onNodeWithText("Debt name *").performScrollTo().performTextInput("Test Loan")
    composeTestRule.onNodeWithText("Remaining balance *").performScrollTo().performTextInput("200")
    composeTestRule.onNodeWithText("Per payment").performScrollTo().performTextInput("50")
    composeTestRule.onNodeWithText("Save debt").performScrollTo().performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      runCatching {
        composeTestRule.onNodeWithText("Test Loan").assertIsDisplayed()
        true
      }.getOrDefault(false)
    }

    // 2. Open Debt Detail & inspect expandable Loan details
    composeTestRule.onNodeWithText("Test Loan").performClick()
    composeTestRule.onNodeWithText("Balance remaining").assertIsDisplayed()
    scrollLazyListTo("Loan details")
    composeTestRule.onNodeWithText("Loan details").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Original principal").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithContentDescription("Back").performClick()

    // 3. Return Home & log expense + record payment via bottom sheets
    composeTestRule.onNodeWithText("Home").performClick()
    composeTestRule.onNodeWithText("Add expense").performClick()
    composeTestRule.onNodeWithText("Save expense").performScrollTo().performClick()

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Record payment").performClick()
    composeTestRule.onNodeWithText("Confirm payment").performScrollTo().performClick()

    // Balance should now be ₱150.00 (₱200 - ₱50)
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      runCatching {
        composeTestRule.onAllNodesWithText("₱150.00").onFirst().assertIsDisplayed()
        true
      }.getOrDefault(false)
    }

    // 4. Clean up via More -> Backup and restore -> Clear all data
    composeTestRule.onNodeWithText("More").performClick()
    scrollLazyListTo("Backup and restore")
    composeTestRule.onNodeWithText("Backup and restore").performClick()
    composeTestRule.onNodeWithText("Clear all data").performClick()
    composeTestRule.onNodeWithText("Continue").performClick()
    composeTestRule.onNodeWithText("Delete everything").performClick()
    composeTestRule.onNodeWithContentDescription("Back").performClick()
    composeTestRule.onNodeWithText("Home").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      runCatching {
        composeTestRule.onAllNodesWithText("₱0.00").onFirst().assertIsDisplayed()
        true
      }.getOrDefault(false)
    }
  }
}
