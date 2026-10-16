@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.fajar.starter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.LocalBackGestureDispatcher
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.app.di.applicationNetworkModule
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.database.*
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.documents.data.replica.repositories.EncryptedDocumentCatalogRepository
import dev.fajar.starter.documents.domain.entities.LocalDocumentFile
import dev.fajar.starter.documents.domain.replica.repositories.DocumentCatalogRepository
import dev.fajar.starter.documents.domain.repositories.DocumentFileRepository
import dev.fajar.starter.documents.domain.usecases.ImportDocumentFile
import dev.fajar.starter.documents.domain.usecases.ObserveDocumentFiles
import dev.fajar.starter.documents.presentation.detail.DocumentFileEvent
import dev.fajar.starter.documents.presentation.detail.DocumentFileViewModel
import dev.fajar.starter.fileio.*
import dev.fajar.starter.fixtures.DocumentInputUi
import dev.fajar.starter.fixtures.DocumentOutputUi
import dev.fajar.starter.fixtures.NativeDocumentsUi
import dev.fajar.starter.fixtures.NativeInboxCacheUi
import dev.fajar.starter.fixtures.NativePayslipUi
import dev.fajar.starter.fixtures.NativeSessionsUi
import dev.fajar.starter.fixtures.NativeUiServer
import dev.fajar.starter.fixtures.NativeWorkforceReplicaUi
import dev.fajar.starter.fixtures.TestBackDispatcher
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.data.repositories.EncryptedInboxCacheRepository
import dev.fajar.starter.inbox.domain.repositories.InboxCacheRepository
import dev.fajar.starter.localization.formatLocalDate
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.people.presentation.directory.EmployeesViewModel
import dev.fajar.starter.workforce.domain.replica.repositories.WorkforceReplicaRepository
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

class AppFlowTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val testName = TestName()
    private val backDispatcher = TestBackDispatcher()

    @Test
    fun nativeExpensesKeepApprovalSeparateFromPaymentAndRestoreAdmittedDetailsOffline() {
        val server = NativeUiServer().apply { expenses.enabled = true }
        val lifecycle = mutableStateOf(Lifecycle.State.RESUMED)
        withApplication(server, lifecycleState = lifecycle) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNodeWithText("All services").performScrollTo().performClick()
            waitFor("Expenses")
            compose
                .onNode(hasText("Expenses") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Client visit")
            compose.onNodeWithText("IDR 150,000").assertIsDisplayed()
            capture("expenses-overview")
            assertEquals(0, server.expenses.lists.get())
            compose.onNodeWithContentDescription("Previous month").performClick()
            waitFor("No claims for this month.")
            compose.onNodeWithText("September 2026").assertIsDisplayed()
            compose.onNodeWithContentDescription("Next month").performClick()
            waitFor("Client visit")
            compose.onNode(hasText("Client visit") and hasClickAction()).performClick()
            waitFor("Claim amount")
            capture("expense-detail")
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText("Transport receipt.pdf"))
            compose.onNodeWithText("Transport receipt.pdf").assertIsDisplayed()
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText("Payment has not been prepared."))
            compose.onNodeWithText("Paid").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.onNodeWithContentDescription("Refresh").assertIsDisplayed()
            capture("expense-approval-payment")
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText("Earlier activity"))
            compose.onNode(hasText("Earlier activity") and hasClickAction()).performClick()
            compose.waitUntil { server.expenses.historyReads.get() == 1 }
            // The history page is a local observation; HTTP completion and the header's
            // refresh action do not establish that the selected page has reached the UI.
            compose.waitForIdle()
            waitFor("Latest activity")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Draft saved"))
            compose.onNodeWithText("Draft saved").assertIsDisplayed()
            refreshWhenReady { server.expenses.offline = true }
            waitFor("Unable to connect. Check your connection.")
            compose.runOnIdle { lifecycle.value = Lifecycle.State.CREATED }
            compose.waitForIdle()
            compose.runOnIdle { lifecycle.value = Lifecycle.State.RESUMED }
            waitFor("Draft saved")
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            waitFor("Claim amount")
            compose.onNodeWithText("Client visit").assertIsDisplayed()
            server.expenses.offline = false
        }
    }

    @Test
    fun nativeExpenseFiltersAndFixedNavigationRemainUsableAtLargeText() {
        val server = NativeUiServer().apply { expenses.enabled = true }
        withApplication(server, fontScale = 2f) {
            signIn()
            waitFor("Choose a company")
            compose
                .onNode(hasText("PT Nusa Digital") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Welcome")
            compose.onNodeWithText("All services").performScrollTo().performClick()
            waitFor("Expenses")
            compose
                .onNode(hasText("Expenses") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Client visit")
            waitForAction("October 2026")
            compose.onNode(hasText("October 2026") and hasClickAction()).performClick()
            waitFor("November")
            compose
                .onNode(hasText("November") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("No claims for this month.")
            compose.waitUntil(15_000) {
                compose
                    .onAllNodes(hasContentDescription("Previous month") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithContentDescription("Previous month").performClick()
            waitFor("Client visit")
            waitForAction("All statuses")
            compose.onNode(hasText("All statuses") and hasClickAction()).performClick()
            waitFor("Claim status")
            compose
                .onNode(hasText("Needs correction") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("No claims for this month.")
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.onNodeWithContentDescription("Refresh").assertIsDisplayed()
            capture("expense-filters-large-text")
            waitForAction("Needs correction")
            compose.onNode(hasText("Needs correction") and hasClickAction()).performClick()
            waitFor("Claim status")
            compose
                .onNode(hasText("Approved") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Client visit")
            compose
                .onNode(hasText("Client visit") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Claim amount")
            capture("expense-detail-large-text")
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText("Transport receipt.pdf"))
            compose.onNodeWithText("Transport receipt.pdf").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        }
    }

    @Test
    fun payslipHistoryKeepsCompactControlsAtLargeTextAndReopensOwnedRevisionsOffline() {
        val server =
            NativeUiServer().apply {
                payroll.enabled = true
                payroll.amended = true
            }
        val output = DocumentOutputUi("payslip-${NativePayslipUi.ID}-v0-id.pdf")
        withApplication(server, fileOutputs = output, fontScale = 2f) {
            signIn()
            waitFor("Choose a company")
            compose
                .onNode(hasText("PT Nusa Digital") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Welcome")
            compose.onNodeWithText("All services").performScrollTo().performClick()
            waitFor("Payslips")
            compose
                .onNode(hasText("Payslips") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("September 2026")
            compose.onNode(hasText("September 2026") and hasClickAction()).performClick()
            waitForPayslipRevision(2, 2)
            compose
                .onNodeWithContentDescription("Previous revision")
                .assertIsDisplayed()
                .performClick()
            waitForPayslipRevision(1, 2)
            compose.onNodeWithContentDescription("Previous revision").performClick()
            waitForPayslipRevision(0, 2)
            assertEquals(2, server.payroll.historyReads.get())
            compose.onNodeWithContentDescription("Previous revision").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Next revision").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.onNodeWithText("Latest").assertIsDisplayed()
            capture("payslip-history-large-text")
            server.payroll.offline = true
            server.companyPolicy.offline = true
            compose.onNodeWithText("Latest").performClick()
            waitForPayslipRevision(2, 2)
            compose.onNodeWithContentDescription("Previous revision").performClick()
            waitForPayslipRevision(1, 2)
            compose.onNodeWithContentDescription("Previous revision").performClick()
            waitForPayslipRevision(0, 2)
            assertEquals(2, server.payroll.historyReads.get())
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Transferred"))
            compose
                .onAllNodesWithText("10,000,000.75", useUnmergedTree = true)[0]
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(action(layouts))
                    assertEquals(1, layouts.single().lineCount)
                }
            capture("payslip-history-payment-large-text")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Overpayment"))
            compose.onNodeWithText("Balance for revision 3 · IDR").assertExists()
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.onNodeWithContentDescription("Next revision").assertIsDisplayed()
            server.payroll.offline = false
            server.companyPolicy.offline = false
            compose.onNodeWithContentDescription("Save PDF").performClick()
            waitFor("Choose the PDF language. Saved files are managed outside HRIS.")
            compose
                .onNode(hasText("Bahasa Indonesia") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("PDF saved")
            assertEquals(listOf(0L, 0L, 0L), server.payroll.pdfRevisions.toList())
            assertEquals(server.payroll.sha256, output.completedHash)
        }
    }

    @Test
    fun revisedPayslipKeepsItsOwnBalanceRevisionAndExportsTheDisplayedAssessment() {
        val server =
            NativeUiServer().apply {
                payroll.enabled = true
                payroll.amended = true
            }
        val output = DocumentOutputUi("payslip-${NativePayslipUi.ID}-v2-id.pdf")
        withApplication(server, fileOutputs = output) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNodeWithText("All services").performScrollTo().performClick()
            waitFor("Payslips")
            compose
                .onNode(hasText("Payslips") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("September 2026")
            compose.onNode(hasText("September 2026") and hasClickAction()).performClick()
            waitFor("Revision 2")
            compose.onNodeWithText("IDR 10,232,500").assertIsDisplayed()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Overpayment"))
            compose.onNodeWithText("Balance for revision 3 · IDR").assertIsDisplayed()
            compose.onNodeWithText("599,999.75", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("400,000.25", useUnmergedTree = true).assertIsDisplayed()
            capture("payslip-revised-balance")
            compose.onNodeWithContentDescription("Save PDF").assertIsDisplayed().performClick()
            waitFor("Choose the PDF language. Saved files are managed outside HRIS.")
            compose.onNode(hasText("Bahasa Indonesia") and hasClickAction()).performClick()
            waitFor("PDF saved")
            assertEquals(server.payroll.sha256, output.completedHash)
            assertEquals(2, server.payroll.inspections.get())
            assertEquals(1, output.releases.get())
            assertEquals(0, server.payroll.lists.get())
        }
    }

    @Test
    fun nativePayslipsShowPublishedAmountsAndSaveWhileActivityIsStopped() {
        val server = NativeUiServer().apply { payroll.enabled = true }
        val output = DocumentOutputUi("payslip-${NativePayslipUi.ID}-v0-id.pdf")
        val choice = CompletableDeferred<Boolean>()
        output.selection = choice
        val lifecycle = mutableStateOf(Lifecycle.State.RESUMED)
        withApplication(server, fileOutputs = output, lifecycleState = lifecycle) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNodeWithText("All services").performScrollTo().performClick()
            waitFor("Payslips")
            compose
                .onNode(hasText("Payslips") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("September 2026")
            compose.onNodeWithText("IDR 10,232,500").assertIsDisplayed()
            capture("payslips-overview")
            compose.onNodeWithContentDescription("Previous year").performClick()
            waitFor("No payslips for this year.")
            compose.onNodeWithText("2025").assertIsDisplayed()
            compose.onNodeWithContentDescription("Next year").performClick()
            waitFor("September 2026")
            compose.onNode(hasText("September 2026") and hasClickAction()).performClick()
            waitFor("Payment not recorded")
            assertEquals(0, server.payroll.lists.get())
            assertEquals(1, server.payroll.details.get())
            compose.onNodeWithText("Paid").assertDoesNotExist()
            compose.onNodeWithText("IDR 10,232,500").assertIsDisplayed()
            capture("payslip-detail")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Deductions"))
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.onNodeWithContentDescription("Save PDF").assertIsDisplayed().performClick()
            waitFor("Choose the PDF language. Saved files are managed outside HRIS.")
            capture(
                "payslip-language",
                isRoot() and
                    hasAnyDescendant(
                        hasText("Choose the PDF language. Saved files are managed outside HRIS.")
                    ),
            )
            compose.onNode(hasText("Bahasa Indonesia") and hasClickAction()).performClick()
            compose.waitUntil { output.active }
            compose.runOnIdle { lifecycle.value = Lifecycle.State.CREATED }
            compose.waitForIdle()
            assertTrue(output.active)
            assertEquals(0, output.releases.get())
            choice.complete(true)
            compose.waitUntil { output.completedHash != null }
            compose.runOnIdle { lifecycle.value = Lifecycle.State.RESUMED }
            waitFor("PDF saved")
            assertEquals(server.payroll.sha256, output.completedHash)
            assertEquals(1, output.selections.get())
            assertEquals(1, output.releases.get())
            assertTrue(output.buffers.isNotEmpty())
            assertTrue(output.buffers.all { buffer -> buffer.all { it == 0.toByte() } })
            assertEquals(1, server.payroll.downloads.get())
        }
    }

    @Test
    fun nativePayslipLargeTextKeepsActionsReachableAndBackCancelsThePicker() {
        val server = NativeUiServer().apply { payroll.enabled = true }
        val output = DocumentOutputUi("payslip-${NativePayslipUi.ID}-v0-id.pdf")
        val choice = CompletableDeferred<Boolean>()
        output.selection = choice
        withApplication(server, fileOutputs = output, fontScale = 2f) {
            signIn()
            waitFor("Choose a company")
            compose
                .onNode(hasText("PT Nusa Digital") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Welcome")
            compose.onNodeWithText("All services").performScrollTo().performClick()
            waitFor("Payslips")
            compose
                .onNode(hasText("Payslips") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("September 2026")
            compose.onNode(hasText("September 2026") and hasClickAction()).performClick()
            waitFor("Take-home pay")
            capture("payslip-large-text")
            compose.onNodeWithContentDescription("Save PDF").assertIsDisplayed().performClick()
            waitFor("Choose the PDF language. Saved files are managed outside HRIS.")
            compose
                .onNode(hasText("Bahasa Indonesia") and hasClickAction())
                .performScrollTo()
                .performClick()
            compose.waitUntil { output.active }
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("September 2026")
            compose.waitUntil { output.releases.get() == 1 }
            choice.complete(true)
            compose.waitForIdle()
            assertFalse(output.active)
            assertEquals(0, output.writes.get())
            assertNull(output.completedHash)
        }
    }

    @Test
    fun overtimeActualRecoveryPreservesItsActionBeforeExplicitWithdrawal() {
        val server = NativeUiServer().apply { overtime.enabled = true }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose
                .onNode(hasText("Overtime") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("17:00 – 19:00")
            compose.onNode(hasText("17:00 – 19:00") and hasClickAction()).performClick()
            waitFor("Overtime request")
            waitForAction("Submit actual time")
            compose.onNode(hasText("Submit actual time") and hasClickAction()).performClick()
            waitFor("Actual time")
            waitForAction("Submit actual time")
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText("Start time") and hasClickAction())
            compose.onNode(hasText("Start time") and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("Minute (0–59)").performTextReplacement("05")
            compose.onNode(hasText("Set time") and hasClickAction()).performClick()
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText("End time") and hasClickAction())
            compose.onNode(hasText("End time") and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("Hour (0–23)").performTextReplacement("18")
            compose.onNodeWithContentDescription("Minute (0–59)").performTextReplacement("55")
            compose.onNode(hasText("Set time") and hasClickAction()).performClick()
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasContentDescription("Reason"))
            compose.onNodeWithContentDescription("Reason").performTextReplacement("Count completed")
            capture("overtime-actual-form")
            server.overtime.loseNextActionResponse = true
            compose.onNode(hasText("Submit actual time") and hasClickAction()).performClick()
            waitFor("Delivery has not been confirmed. Retry the saved request.")
            val original = server.overtime.actualRequests.single()
            val input = Json.parseToJsonElement(original.second).jsonObject
            assertEquals(
                "2026-10-12T10:05:00Z",
                input.getValue("actual").jsonObject.getValue("startsAt").jsonPrimitive.content,
            )
            assertEquals(
                "2026-10-12T11:55:00Z",
                input.getValue("actual").jsonObject.getValue("endsAt").jsonPrimitive.content,
            )
            assertEquals(0L, input.getValue("expectedVersion").jsonPrimitive.long)
            assertEquals(1, server.overtime.actualCommits.get())
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Overtime request")
            waitFor("Pending approval")
            waitForAction("Withdraw request")
            compose.onNode(hasText("Withdraw request") and hasClickAction()).performClick()
            waitFor("Delivery has not been confirmed. Retry the saved request.")
            compose.onNodeWithText("Actual time").assertExists()
            compose.onNodeWithText("This will withdraw the overtime request.").assertDoesNotExist()
            waitForAction("Retry delivery")
            compose.onNode(hasText("Retry delivery") and hasClickAction()).performClick()
            waitFor("Overtime request")
            assertEquals(listOf(original, original), server.overtime.actualRequests.toList())
            assertEquals(1, server.overtime.actualCommits.get())
            waitForAction("Withdraw request")
            compose.onNode(hasText("Withdraw request") and hasClickAction()).performClick()
            waitFor("This will withdraw the overtime request.")
            waitForAction("Withdraw request")
            assertEquals(0, server.overtime.withdrawalCommits.get())
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Overtime request")
            waitForAction("Withdraw request")
            compose.onNode(hasText("Withdraw request") and hasClickAction()).performClick()
            waitFor("This will withdraw the overtime request.")
            waitForAction("Withdraw request")
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasContentDescription("Reason"))
            compose.onNodeWithContentDescription("Reason").performTextReplacement("Work cancelled")
            capture("overtime-withdrawal-form")
            compose.onNode(hasText("Withdraw request") and hasClickAction()).performClick()
            waitFor("Overtime request")
            waitFor("Withdrawn")
            assertEquals(1, server.overtime.withdrawalCommits.get())
            val withdrawal =
                Json.parseToJsonElement(server.overtime.withdrawalRequests.single().second)
                    .jsonObject
            assertEquals(1L, withdrawal.getValue("expectedVersion").jsonPrimitive.long)
            assertEquals("Work cancelled", withdrawal.getValue("reason").jsonPrimitive.content)
            compose.onNode(hasText("Submit actual time") and hasClickAction()).assertDoesNotExist()
            compose.onNode(hasText("Withdraw request") and hasClickAction()).assertDoesNotExist()
            capture("overtime-withdrawn-request")
        }
    }

    @Test
    fun overtimePlanningRestoresItsSavedInputAndRecoversALostCommitResponse() {
        val server = NativeUiServer().apply { overtime.enabled = true }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose
                .onNode(hasText("Overtime") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("17:00 – 19:00")
            waitForAction("Plan overtime")
            compose.onNode(hasText("Plan overtime") and hasClickAction()).performClick()
            waitFor("Work schedule")
            waitForAction("Submit plan")
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText("Start time") and hasClickAction())
            compose.onNode(hasText("Start time") and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("Hour (0–23)").performTextReplacement("18")
            compose.onNodeWithContentDescription("Minute (0–59)").performTextReplacement("30")
            compose.onNode(hasText("Set time") and hasClickAction()).performClick()
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText("End time") and hasClickAction())
            compose.onNode(hasText("End time") and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("Hour (0–23)").performTextReplacement("20")
            compose.onNodeWithContentDescription("Minute (0–59)").performTextReplacement("30")
            compose.onNode(hasText("Set time") and hasClickAction()).performClick()
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasContentDescription("Break (minutes)"))
            compose.onNodeWithContentDescription("Break (minutes)").performTextReplacement("15")
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasContentDescription("Reason"))
            compose
                .onNodeWithContentDescription("Reason")
                .performTextReplacement("Month-end inventory")
            capture("overtime-plan-form")
            server.overtime.loseNextPlanResponse = true
            compose.onNode(hasText("Submit plan") and hasClickAction()).performClick()
            waitFor("Delivery has not been confirmed. Retry the saved request.")
            assertEquals(1, server.overtime.planCommits.get())
            assertEquals(1, server.overtime.planRequests.size)
            val original = server.overtime.planRequests.single()
            val body = Json.parseToJsonElement(original.second).jsonObject
            assertEquals("Month-end inventory", body.getValue("reason").jsonPrimitive.content)
            assertEquals(
                "2026-10-12T11:30:00Z",
                body.getValue("requested").jsonObject.getValue("startsAt").jsonPrimitive.content,
            )
            val reviews = server.overtime.planningReads.get()
            capture("overtime-plan-unconfirmed")
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("17:00 – 19:00")
            compose.waitUntil {
                compose
                    .onAllNodes(hasText("Plan overtime") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNode(hasText("Plan overtime") and hasClickAction()).performClick()
            waitFor("Delivery has not been confirmed. Retry the saved request.")
            compose.onNode(hasText("Start time") and hasClickAction()).assertDoesNotExist()
            assertEquals(reviews, server.overtime.planningReads.get())
            compose
                .onNode(hasText("Retry delivery") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Overtime request")
            waitFor("Month-end inventory")
            assertEquals(1, server.overtime.planCommits.get())
            assertEquals(listOf(original, original), server.overtime.planRequests.toList())
            compose.onNode(hasText("18:30 – 20:30") and hasClickAction().not()).assertExists()
            capture("overtime-planned-request")
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("17:00 – 19:00")
            compose.onNodeWithText("Retry delivery").assertDoesNotExist()
        }
    }

    @Test
    fun overtimeUsesFeatureRoutesAndRecoversItsAdmittedListAndDetailsOffline() {
        val server = NativeUiServer().apply { overtime.enabled = true }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose
                .onNode(hasText("Overtime") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("17:00 – 19:00")
            capture("overtime-list")
            compose.onNode(hasText("17:00 – 19:00") and hasClickAction()).performClick()
            waitFor("Overtime request")
            waitFor("Inventory count")
            capture("overtime-request")
            server.overtime.offline = true
            refreshWhenReady()
            waitFor("Unable to connect. Check your connection.")
            compose.onNodeWithText("Planned time").assertExists()
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("17:00 – 19:00")
            val readsBeforeFilter = server.overtime.detailReads.get()
            compose.onNode(hasText("All statuses") and hasClickAction()).performClick()
            waitFor("Request status")
            compose.onNode(hasText("Approved") and isSelectable()).performClick()
            waitFor("No requests for this period and status.")
            compose.onNode(hasText("17:00 – 19:00") and hasClickAction()).assertDoesNotExist()
            assertEquals(readsBeforeFilter, server.overtime.detailReads.get())
            server.overtime.offline = false
            refreshWhenReady()
            waitFor("No requests for this period and status.")
            compose.onNode(hasText("Approved") and hasClickAction()).performClick()
            waitFor("Request status")
            compose.onNode(hasText("All statuses") and isSelectable()).performClick()
            waitFor("17:00 – 19:00")
            assertEquals(0, server.overtime.listReads.get())
            assertTrue(server.overtime.syncReads.get() >= 2)
            assertTrue(server.overtime.detailReads.get() >= 2)
            server.overtime.failureCode = "company_module_disabled"
            refreshWhenReady()
            waitFor("This service is unavailable for this company.")
            compose.onNode(hasText("17:00 – 19:00") and hasClickAction()).assertDoesNotExist()
        }
    }

    @Test
    fun companySelectionShowsPendingWorkAndRecoversWithoutDuplicateActions() {
        val pending = CompletableDeferred<Unit>()
        val server = NativeUiServer().apply { companyPolicy.pending = pending }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Opening company…")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).assertIsNotEnabled()
            compose.onNode(hasText("PT Lintas Karya") and hasClickAction()).assertIsNotEnabled()
            compose.onNode(hasText("Sign out") and hasClickAction()).assertIsNotEnabled()
            compose.waitUntil { server.companyPolicy.reads.get() == 1 }
            assertEquals(1, server.companyPolicy.reads.get())
            server.companyPolicy.offline = true
            pending.complete(Unit)
            waitFor("Unable to connect. Check your connection.")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).assertIsEnabled()
            compose.onNode(hasText("PT Lintas Karya") and hasClickAction()).assertIsEnabled()
            server.companyPolicy.pending = null
            server.companyPolicy.offline = false
            compose.onNode(hasText("PT Lintas Karya") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNodeWithText("PT Lintas Karya").assertExists()
        }
    }

    @Test
    fun publishedDocumentsResumeFromCommittedProgressAndExportVerifiedContent() {
        val server =
            NativeUiServer().apply {
                documents.enabled = true
                documents.downloads.enabled = true
            }
        val output = DocumentOutputUi()
        val pending = CompletableDeferred<Unit>()
        server.documents.downloads.continuation = pending
        withApplication(server, fileOutputs = output) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose
                .onNode(hasText("Documents") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Medical certificate")
            compose.onNode(hasText("Receipt") and isSelectable()).performClick()
            waitFor("No published documents in this category.")
            compose.onNode(hasText("Personal") and isSelectable()).performClick()
            waitFor("Medical certificate")
            capture("document-catalog")
            compose.onNode(hasText("Medical certificate") and hasClickAction()).performClick()
            waitFor("Pause download")
            compose.waitUntil(15_000) {
                server.documents.downloads.offsets.size == 2 &&
                    server.documents.downloads.active.get() == 1
            }
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Medical certificate")
            compose.waitUntil { server.documents.downloads.active.get() == 0 }
            compose.onNodeWithContentDescription("On this device").performClick()
            waitFor("Download incomplete")
            compose
                .onNode(hasText("certificate.pdf") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Resume download")
            assertEquals(listOf(0L, 1_048_576L), server.documents.downloads.offsets.toList())
            server.documents.downloads.continuation = null
            pending.complete(Unit)
            compose.onNodeWithText("Resume download").performClick()
            waitFor("Save a copy")
            assertEquals(
                listOf(0L, 1_048_576L, 1_048_576L, 2_097_152L),
                server.documents.downloads.offsets.toList(),
            )
            compose.onNodeWithText("Save a copy").assertIsDisplayed().performClick()
            waitFor("Copy saved.")
            assertEquals(server.documents.downloads.sha256, output.completedHash)
            assertEquals(3, output.writes.get())
            assertEquals(1, output.releases.get())
            assertTrue(output.buffers.all { buffer -> buffer.all { it == 0.toByte() } })
            assertFalse(output.active)
            capture("document-download-saved")
        }
    }

    @Test
    fun publishedCatalogFiltersAndRouteReturnUseLocalRecordsWhileOffline() {
        val server = NativeUiServer().apply { documents.enabled = true }
        val lifecycle = mutableStateOf(Lifecycle.State.RESUMED)
        withApplication(server, lifecycleState = lifecycle) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose
                .onNode(hasText("Documents") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Medical certificate")
            compose.waitUntil {
                compose
                    .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val networkReads = server.documents.catalogReads.get()
            server.documents.catalogOffline = true
            compose.onNode(hasText("Receipt") and isSelectable()).performClick()
            waitFor("No published documents in this category.")
            compose.onNode(hasText("Personal") and isSelectable()).performClick()
            waitFor("Medical certificate")
            compose.runOnIdle { lifecycle.value = Lifecycle.State.CREATED }
            compose.waitForIdle()
            compose.runOnIdle { lifecycle.value = Lifecycle.State.RESUMED }
            waitFor("Medical certificate")
            compose.onNodeWithContentDescription("On this device").performClick()
            waitFor("No local files yet.")
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Medical certificate")
            assertEquals(networkReads, server.documents.catalogReads.get())
            refreshWhenReady()
            waitFor("Unable to connect. Check your connection.")
            compose.onNodeWithText("Medical certificate").assertExists()
            compose.onNode(hasText("Receipt") and isSelectable()).performClick()
            waitFor("No published documents in this category.")
        }
    }

    @Test
    fun publishedCatalogReopensATerminatedStorageSourceAfterRefresh() {
        val server = NativeUiServer().apply { documents.enabled = true }
        val failure = CompletableDeferred<Unit>()
        val armed = AtomicBoolean(true)
        val active = AtomicInteger()
        val definitions = module {
            single<DocumentCatalogRepository> {
                val original: ReplicaStore = get()
                val source =
                    object : ReplicaStore by original {
                        override fun observeQueries(
                            scope: ReplicaScope,
                            partition: String,
                            queries: List<ReplicaQuery>,
                        ) = flow {
                            active.incrementAndGet()
                            try {
                                coroutineScope {
                                    val interruption = launch {
                                        failure.await()
                                        if (armed.compareAndSet(true, false))
                                            throw java.io.IOException(
                                                "Simulated closed storage source"
                                            )
                                    }
                                    try {
                                        emitAll(original.observeQueries(scope, partition, queries))
                                    } finally {
                                        interruption.cancel()
                                    }
                                }
                            } finally {
                                active.decrementAndGet()
                            }
                        }
                    }
                EncryptedDocumentCatalogRepository(
                    source,
                    get(),
                    get(named("document-catalog-content-key")),
                    get(),
                )
            }
        }
        withApplication(server, overrides = definitions) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose
                .onNode(hasText("Documents") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Medical certificate")
            compose.waitUntil { active.get() == 1 }
            failure.complete(Unit)
            compose.waitUntil { active.get() == 0 && !armed.get() }
            compose.onNodeWithText("Medical certificate").assertExists()
            server.documents.catalogTitle = "Updated certificate"
            server.documents.catalogVersion = 1
            refreshWhenReady()
            waitFor("Updated certificate")
            compose.waitUntil { active.get() == 1 }
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Welcome")
            compose.waitUntil { active.get() == 0 }
        }
    }

    @Test
    fun publishedCatalogDenialRemainsClosedThroughAnOfflineRetry() {
        val server = NativeUiServer().apply { documents.enabled = true }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose
                .onNode(hasText("Documents") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Medical certificate")
            server.documents.catalogDenied = true
            refreshWhenReady()
            waitFor("Your account cannot perform this action.")
            compose.onNodeWithText("Medical certificate").assertDoesNotExist()
            server.documents.catalogDenied = false
            server.documents.catalogOffline = true
            refreshWhenReady()
            compose.waitUntil {
                compose
                    .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Your account cannot perform this action.").assertExists()
            compose.onNodeWithText("Medical certificate").assertDoesNotExist()
            server.documents.catalogOffline = false
            refreshWhenReady()
            waitFor("Medical certificate")
            compose.onNodeWithText("Your account cannot perform this action.").assertDoesNotExist()
        }
    }

    @Test
    fun publishedDocumentDenialStaysClosedUntilContentRevalidationSucceeds() {
        val server =
            NativeUiServer().apply {
                documents.enabled = true
                documents.downloads.enabled = true
            }
        val output = DocumentOutputUi()
        withApplication(server, fileOutputs = output) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose
                .onNode(hasText("Documents") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Medical certificate")
            compose.onNode(hasText("Medical certificate") and hasClickAction()).performClick()
            waitFor("Save a copy")
            server.documents.downloads.denied = true
            compose.onNodeWithText("Save a copy").assertIsDisplayed().performClick()
            waitFor("Your account cannot perform this action.")
            assertEquals(0, output.writes.get())
            assertEquals(1, output.releases.get())
            compose.onNodeWithText("certificate.pdf").assertDoesNotExist()
            server.documents.downloads.denied = false
            server.documents.downloads.offline = true
            compose.onNodeWithText("Try again").performClick()
            compose.waitUntil {
                compose
                    .onAllNodes(hasText("Try again") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Your account cannot perform this action.").assertExists()
            compose.onNodeWithText("certificate.pdf").assertDoesNotExist()
            compose.onNodeWithText("Save a copy").assertDoesNotExist()
            server.documents.downloads.offline = false
            compose.onNodeWithText("Try again").performClick()
            waitFor("Save a copy")
            assertEquals(
                3,
                server.documents.downloads.offsets.size,
                "Recovery validates the existing completion without repeating its ranges",
            )
            compose.onNodeWithText("Save a copy").assertIsDisplayed().performClick()
            waitFor("Copy saved.")
            assertEquals(server.documents.downloads.sha256, output.completedHash)
        }
    }

    @Test
    fun publishedDocumentSaveSurvivesActivityStopAndClosesWhenItsRouteLeaves() {
        val server =
            NativeUiServer().apply {
                documents.enabled = true
                documents.downloads.enabled = true
            }
        val output = DocumentOutputUi()
        val choice = CompletableDeferred<Boolean>()
        output.selection = choice
        val lifecycle = mutableStateOf(Lifecycle.State.RESUMED)
        withApplication(server, fileOutputs = output, lifecycleState = lifecycle) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose
                .onNode(hasText("Documents") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Medical certificate")
            compose.onNode(hasText("Medical certificate") and hasClickAction()).performClick()
            waitFor("Save a copy")
            compose.onNodeWithText("Save a copy").assertIsDisplayed().performClick()
            compose.waitUntil { output.active }
            compose.runOnIdle { lifecycle.value = Lifecycle.State.CREATED }
            compose.waitForIdle()
            assertTrue(output.active)
            assertEquals(0, output.releases.get())
            compose.runOnIdle { lifecycle.value = Lifecycle.State.RESUMED }
            waitFor("Saving copy…")
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Medical certificate")
            compose.waitUntil { output.releases.get() == 1 }
            assertFalse(output.active)
            assertEquals(0, output.writes.get())
            choice.complete(true)
            compose.waitForIdle()
            assertEquals(0, output.writes.get())
            assertNull(output.completedHash)
        }
    }

    @Test
    fun localDocumentsRemainReachableWhileCatalogAcquisitionIsPending() {
        val pending = CompletableDeferred<Unit>()
        val server =
            NativeUiServer().apply {
                documents.enabled = true
                documents.uploadEnabled = true
                documents.gate = pending
            }
        try {
            withApplication(server) {
                signIn()
                waitFor("Choose a company")
                compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
                waitFor("Welcome")
                compose
                    .onNode(hasText("Documents") and hasClickAction())
                    .performScrollTo()
                    .performClick()
                compose.waitUntil { server.documents.active.get() == 1 }
                compose
                    .onNodeWithContentDescription("On this device")
                    .assertIsEnabled()
                    .performClick()
                waitFor("No local files yet.")
                compose.waitUntil { server.documents.active.get() == 0 }
                pending.complete(Unit)
                compose.waitForIdle()
                compose.onNodeWithText("Medical certificate").assertDoesNotExist()
                compose.onNodeWithContentDescription("Add file").assertIsEnabled()
                assertEquals(1, server.documents.reads.get())
            }
        } finally {
            pending.complete(Unit)
        }
    }

    @Test
    fun documentFormRetainsEditsAcrossDetachmentWhileRenewingReadAdmission() {
        val server =
            NativeUiServer().apply {
                documents.enabled = true
                documents.uploadEnabled = true
            }
        val owners = mutableListOf<DocumentFileViewModel>()
        val definitions = module {
            factory<DocumentFileViewModel> { parameters ->
                DocumentFileViewModel(parameters.get(), get(), get(), get(), get(), get(), get())
                    .also { owners += it }
            }
        }
        withApplication(server, definitions, fileInputs = DocumentInputUi("draft.pdf")) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            openDocumentFiles()
            waitFor("No local files yet.")
            compose.onNodeWithContentDescription("Add file").performClick()
            waitFor("Upload document")
            compose
                .onNodeWithContentDescription("Title")
                .performScrollTo()
                .performTextReplacement("Retained title")
            compose
                .onNodeWithContentDescription("Description")
                .performScrollTo()
                .performTextInput("Retained description")
            val referenceReads = server.documents.reads.get()
            val owner = owners.single()
            compose.runOnIdle { owner.onEvent(DocumentFileEvent.Closed) }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Title").assertDoesNotExist()
            assertEquals("Retained title", owner.state.value.form?.title)
            compose.runOnIdle { owner.onEvent(DocumentFileEvent.Opened) }
            waitFor("Upload document")
            compose.onNodeWithContentDescription("Title").assertTextContains("Retained title")
            compose
                .onNodeWithContentDescription("Description")
                .assertTextContains("Retained description")
            assertEquals(
                referenceReads,
                server.documents.reads.get(),
                "Restoring the owned form does not reacquire its employment directory",
            )
            assertEquals(0, server.documents.uploads.starts.get())
        }
    }

    @Test
    fun documentFormValidatesConfirmationAndPublishesOnlyAfterTheServerCheck() {
        val server =
            NativeUiServer().apply {
                documents.enabled = true
                documents.uploadEnabled = true
            }
        withApplication(server, fileInputs = DocumentInputUi("receipt.pdf")) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            openDocumentFiles()
            waitFor("No local files yet.")
            compose.onNodeWithContentDescription("Add file").performClick()
            waitFor("Upload document")
            compose.onNodeWithText("EMP-001").assertExists()
            compose.onNode(hasText("Upload document") and hasClickAction()).performClick()
            waitFor("Enter a description of up to 1,000 characters.")
            assertEquals(0, server.documents.uploads.starts.get())
            compose
                .onNodeWithContentDescription("Title")
                .performScrollTo()
                .performTextReplacement("Travel receipt")
            compose
                .onNodeWithContentDescription("Description")
                .performScrollTo()
                .performTextInput("Reimbursement evidence")
            compose.onNode(hasText("Receipt") and hasClickAction()).performScrollTo().performClick()
            capture("document-upload-form")
            compose.onNode(hasText("Upload document") and hasClickAction()).performClick()
            waitFor("Ready")
            assertEquals(1, server.documents.uploads.starts.get())
            assertEquals(1, server.documents.uploads.chunks.get())
            assertEquals(1, server.documents.uploads.validations.get())
            assertEquals(
                "RECEIPT",
                server.documents.uploads.submitted?.get("classification")?.jsonPrimitive?.content,
            )
            assertEquals(
                "Travel receipt",
                server.documents.uploads.submitted?.get("title")?.jsonPrimitive?.content,
            )
            capture("document-upload-ready")
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("On this device")
            compose.onNodeWithText("Ready").assertExists()
            compose.onNodeWithContentDescription("Remove receipt.pdf from device").performClick()
            compose.onNode(hasText("Remove local file") and hasClickAction()).performClick()
            waitFor("No local files yet.")
            assertEquals(1, server.documents.uploads.starts.get())
        }
    }

    @Test
    fun leavingTheDocumentFormCancelsPendingEmploymentAcquisition() {
        val server =
            NativeUiServer().apply {
                documents.enabled = true
                documents.uploadEnabled = true
            }
        withApplication(server, fileInputs = DocumentInputUi("pending.pdf")) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            openDocumentFiles()
            waitFor("No local files yet.")
            val pending = CompletableDeferred<Unit>()
            val referenceReads = server.documents.reads.get()
            server.documents.gate = pending
            compose.onNodeWithContentDescription("Add file").performClick()
            compose.waitUntil { server.documents.active.get() == 1 }
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("On this device")
            compose.waitUntil { server.documents.active.get() == 0 }
            server.documents.gate = null
            pending.complete(Unit)
            compose.waitForIdle()
            assertEquals(0, server.documents.uploads.starts.get())
            compose.onNode(hasText("pending.pdf") and hasClickAction()).performClick()
            waitFor("Upload document")
            compose.onNodeWithText("EMP-001").assertExists()
            assertEquals(
                referenceReads + 2,
                server.documents.reads.get(),
                "One cancelled acquisition and one admitted form acquisition",
            )
        }
    }

    @Test
    fun deniedUploadRetainsTheFileAndRequiresSuccessfulRevalidationBeforeReopening() {
        val server =
            NativeUiServer().apply {
                documents.enabled = true
                documents.uploadEnabled = true
                documents.uploads.denied = true
            }
        withApplication(server, fileInputs = DocumentInputUi("retained-upload.pdf")) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            openDocumentFiles()
            waitFor("No local files yet.")
            compose.onNodeWithContentDescription("Add file").performClick()
            waitFor("Upload document")
            compose
                .onNodeWithContentDescription("Description")
                .performScrollTo()
                .performTextInput("Personal record")
            compose.onNode(hasText("Upload document") and hasClickAction()).performClick()
            waitFor("Try again")
            compose.onNodeWithText("retained-upload.pdf").assertDoesNotExist()
            server.companyPolicy.offline = true
            val requests = server.companyPolicy.reads.get()
            compose.onNode(hasText("Try again") and hasClickAction()).performClick()
            compose.waitUntil { server.companyPolicy.reads.get() > requests }
            compose.waitUntil {
                compose
                    .onAllNodes(hasText("Try again") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("retained-upload.pdf").assertDoesNotExist()
            server.companyPolicy.offline = false
            server.documents.uploads.denied = false
            compose.onNodeWithText("retained-upload.pdf").assertDoesNotExist()
            compose.onNode(hasText("Try again") and hasClickAction()).performClick()
            waitFor("retained-upload.pdf")
            compose.onNodeWithText("Needs attention").assertExists()
        }
    }

    @Test
    fun documentImportUsesTheAssembledGraphAndTheContainerClosesItsInputSource() {
        val server =
            NativeUiServer().apply {
                documents.enabled = true
                documents.uploadEnabled = true
            }
        val selected = mutableListOf<String>()
        val released = mutableListOf<String>()
        var closes = 0
        val source =
            object : FileInputSource {
                override suspend fun select(
                    requestId: String,
                    mediaTypes: Set<String>,
                ): FileInputMetadata {
                    selected += requestId
                    return FileInputMetadata("document.pdf", "application/pdf", 3)
                }

                override fun read(requestId: String) = flowOf(byteArrayOf(1, 2, 3))

                override suspend fun release(requestId: String) {
                    released += requestId
                }

                override fun close() {
                    closes++
                }
            }
        withApplication(server, fileInputs = source) { koin ->
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            val file = runBlocking {
                val session =
                    assertIs<AppResult.Success<Session?>>(koin.get<SessionRepository>().current())
                        .value!!
                val workspace =
                    assertIs<AppResult.Success<Workspace?>>(
                            koin.get<WorkspaceRepository>().current(session.id)
                        )
                        .value!!
                assertIs<AppResult.Success<LocalDocumentFile?>>(
                        koin.get<ImportDocumentFile>()(workspace.id)
                    )
                    .value!!
            }
            assertEquals(3L, file.storedBytes)
            assertNotNull(file.sha256)
            assertEquals(1, selected.size)
            assertEquals(selected, released)
            assertNotNull(koin.get<DocumentFileRepository>())
            openDocumentFiles()
            waitFor("Not uploaded")
            val referenceReads = server.documents.reads.get()
            compose.onNodeWithText("document.pdf").assertExists()
            compose.onNodeWithContentDescription("Remove document.pdf from device").performClick()
            waitFor("This removes the copy stored on this device.")
            compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
            compose.onNodeWithText("Not uploaded").assertExists()
            compose.onNodeWithContentDescription("Remove document.pdf from device").performClick()
            compose.onNode(hasText("Remove local file") and hasClickAction()).performClick()
            waitFor("No local files yet.")
            compose.onNodeWithContentDescription("Add file").performClick()
            waitFor("Upload document")
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Not uploaded")
            assertEquals(2, selected.size)
            assertEquals(selected, released)
            capture("document-local-files")
            assertEquals(
                referenceReads + 1,
                server.documents.reads.get(),
                "Only the new upload form acquires employment references",
            )
            assertEquals(0, closes)
        }
        assertEquals(1, closes)
    }

    @Test
    fun documentReadGateRequiresSuccessfulRevalidationBeforeRetainedFilesReturn() {
        val server =
            NativeUiServer().apply {
                documents.enabled = true
                documents.uploadEnabled = true
            }
        val denied = kotlinx.coroutines.flow.MutableStateFlow(false)
        val observation = module {
            factory<ObserveDocumentFiles> {
                val delegate = get<WorkspaceRepository>()
                val guarded =
                    object : WorkspaceRepository by delegate {
                        override fun observe(sessionId: String) =
                            kotlinx.coroutines.flow.combine(delegate.observe(sessionId), denied) {
                                result,
                                blocked ->
                                if (blocked)
                                    AppResult.Failed(
                                        dev.fajar.starter.common.result.Failure(
                                            dev.fajar.starter.common.result.FailureKind
                                                .AccessDenied,
                                            "Documents are unavailable.",
                                            code = "access_denied",
                                        )
                                    )
                                else result
                            }
                    }
                ObserveDocumentFiles(get(), get(), guarded, kotlin.time.Clock.System, get())
            }
        }
        val source =
            object : FileInputSource {
                override suspend fun select(requestId: String, mediaTypes: Set<String>) =
                    FileInputMetadata("retained.pdf", "application/pdf", 3)

                override fun read(requestId: String) = flowOf(byteArrayOf(1, 2, 3))

                override suspend fun release(requestId: String) = Unit

                override fun close() = Unit
            }
        withApplication(server, observation, fileInputs = source) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            openDocumentFiles()
            waitFor("No local files yet.")
            compose.onNodeWithContentDescription("Add file").performClick()
            waitFor("Upload document")
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Not uploaded")
            compose.runOnIdle { denied.value = true }
            waitFor("Try again")
            compose.onNodeWithText("retained.pdf").assertDoesNotExist()
            server.companyPolicy.offline = true
            val reads = server.companyPolicy.reads.get()
            compose.onNode(hasText("Try again") and hasClickAction()).performClick()
            compose.waitUntil { server.companyPolicy.reads.get() > reads }
            compose.waitUntil {
                compose
                    .onAllNodes(hasText("Try again") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("retained.pdf").assertDoesNotExist()
            server.companyPolicy.offline = false
            compose.runOnIdle { denied.value = false }
            compose.onNodeWithText("retained.pdf").assertDoesNotExist()
            compose.onNode(hasText("Try again") and hasClickAction()).performClick()
            waitFor("Not uploaded")
            compose.onNodeWithText("retained.pdf").assertExists()
        }
    }

    @Test
    fun documentPickerSurvivesActivityStopAndLeavingTheRouteCancelsPendingSelection() {
        val server =
            NativeUiServer().apply {
                documents.enabled = true
                documents.uploadEnabled = true
            }
        val lifecycle = mutableStateOf(Lifecycle.State.RESUMED)
        var selection = CompletableDeferred<FileInputMetadata?>()
        val opened = java.util.concurrent.atomic.AtomicInteger()
        val released = java.util.concurrent.atomic.AtomicInteger()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val source =
            object : FileInputSource {
                override suspend fun select(
                    requestId: String,
                    mediaTypes: Set<String>,
                ): FileInputMetadata? {
                    opened.incrementAndGet()
                    return selection.await()
                }

                override fun read(requestId: String) =
                    kotlinx.coroutines.flow.flow {
                        reads.incrementAndGet()
                        emit(byteArrayOf(1, 2, 3))
                    }

                override suspend fun release(requestId: String) {
                    released.incrementAndGet()
                }

                override fun close() = Unit
            }
        withApplication(server, fileInputs = source, lifecycleState = lifecycle) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            openDocumentFiles()
            waitFor("No local files yet.")
            compose.onNodeWithContentDescription("Add file").performClick()
            compose.waitUntil { opened.get() == 1 }
            compose.runOnIdle { lifecycle.value = Lifecycle.State.CREATED }
            compose.waitForIdle()
            assertEquals(0, released.get(), "External picker stop must not cancel selection")
            selection.complete(FileInputMetadata("picked.pdf", "application/pdf", 3))
            compose.waitUntil { released.get() == 1 }
            compose.runOnIdle { lifecycle.value = Lifecycle.State.RESUMED }
            waitFor("Upload document")
            assertEquals(1, reads.get())
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Not uploaded")
            selection = CompletableDeferred()
            compose.onNodeWithContentDescription("Add file").performClick()
            compose.waitUntil { opened.get() == 2 }
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Medical certificate")
            val metadataReads = server.documents.reads.get()
            compose.waitUntil { released.get() == 2 }
            selection.complete(FileInputMetadata("late.pdf", "application/pdf", 3))
            compose.waitForIdle()
            assertEquals(1, reads.get())
            compose.onNodeWithContentDescription("On this device").performClick()
            waitFor("Not uploaded")
            compose.onNodeWithText("late.pdf").assertDoesNotExist()
            assertEquals(metadataReads, server.documents.reads.get())
        }
    }

    @Test
    fun newLeaveRequestReviewsEvidenceAndRecoversTheSameSubmissionAfterResponseLoss() {
        val server =
            NativeUiServer().apply {
                leave.enabled = true
                leave.creation.attachmentRequired = true
                documents.enabled = true
            }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Leave") and hasClickAction()).performScrollTo().performClick()
            waitFor("12.5 days")
            compose.onNode(hasText("Request leave") and hasClickAction()).performClick()
            waitFor("Select leave type")
            compose
                .onNode(hasText("Start date") and hasClickAction())
                .performScrollTo()
                .performClick()
            compose.onNodeWithContentDescription(formatLocalDate("2026-10-15", "en")).performClick()
            compose.waitUntil {
                compose
                    .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose
                .onNode(hasText("End date") and hasClickAction())
                .performScrollTo()
                .performClick()
            compose.onNodeWithContentDescription(formatLocalDate("2026-10-17", "en")).performClick()
            compose
                .onNode(hasText("Select leave type") and hasClickAction())
                .performScrollTo()
                .performClick()
            compose.onNode(hasText("Annual leave") and hasAnyAncestor(isDialog())).performClick()
            compose
                .onNode(hasText("Daily portion") and hasClickAction())
                .performScrollTo()
                .performClick()
            compose.onNode(hasText("First half") and hasAnyAncestor(isDialog())).performClick()
            compose
                .onNodeWithContentDescription("Reason")
                .performScrollTo()
                .performTextReplacement("Family appointment\nKeep these dates")
            compose
                .onNode(hasText("Review calculation") and hasClickAction())
                .performScrollTo()
                .performClick()
            compose.waitUntil { server.leave.creation.previews.size == 1 }
            compose.waitUntil {
                compose
                    .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Calculation"))
            compose.onNodeWithText("1 day").assertExists()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Excluded dates"))
            compose
                .onNode(hasText(formatLocalDate("2026-10-17", "en")) and hasClickAction().not())
                .assertExists()
            assertEquals(0, server.leave.creation.commits)
            compose.onNode(hasText("Submit request") and hasClickAction()).assertIsNotEnabled()
            val pendingDocuments = CompletableDeferred<Unit>()
            server.documents.gate = pendingDocuments
            compose
                .onNode(hasText("Add attachment") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Select document")
            compose.waitUntil { server.documents.active.get() == 1 }
            compose.onNodeWithContentDescription("Close").performClick()
            compose.waitUntil { server.documents.active.get() == 0 }
            server.documents.gate = null
            pendingDocuments.complete(Unit)
            compose
                .onNode(hasText("Add attachment") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Medical certificate")
            compose
                .onNode(hasText("Medical certificate") and hasAnyAncestor(isDialog()))
                .performClick()
            compose.waitUntil { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("certificate.pdf").assertExists()
            assertEquals(2, server.documents.reads.get())
            assertEquals(1, server.leave.creation.previews.size)
            capture("leave-creation-preview")
            server.leave.creation.loseNextResponse = true
            compose.onNode(hasText("Submit request") and hasClickAction()).performClick()
            waitFor("The delivery result is not yet known.")
            compose.waitUntil {
                compose
                    .onAllNodes(hasText("Retry delivery") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            assertEquals(1, server.leave.creation.commits)
            assertEquals(1, server.leave.creation.requests.size)
            assertEquals(
                listOf(NativeDocumentsUi.REVISION),
                Json.parseToJsonElement(server.leave.creation.requests.single().second)
                    .jsonObject
                    .getValue("attachmentRevisionIds")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )
            capture("leave-creation-unconfirmed")
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Leave balances")
            compose.waitUntil {
                compose
                    .onAllNodes(hasText("Request leave") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            server.leave.offline = true
            compose.onNode(hasText("Request leave") and hasClickAction()).performClick()
            waitFor("The delivery result is not yet known.")
            compose.onNodeWithText("Family appointment\nKeep these dates").assertExists()
            server.leave.offline = false
            compose
                .onNode(hasText("Retry delivery") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Leave request")
            waitFor("Pending approval")
            compose.onNode(hasText("Pending approval") and hasText("Annual leave")).assertExists()
            assertEquals(1, server.leave.creation.commits)
            assertEquals(2, server.leave.creation.requests.size)
            assertEquals(server.leave.creation.requests[0], server.leave.creation.requests[1])
            capture("leave-creation-submitted")
            compose.runOnIdle { backDispatcher.pressBack() }
            waitFor("Leave balances")
        }
    }

    @Test
    fun leaveCancellationRecoversItsLostResponseAndCanWithdrawThePendingCancellation() {
        val server = NativeUiServer().apply { leave.enabled = true }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Leave") and hasClickAction()).performScrollTo().performClick()
            waitFor("12.5 days")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("1.5 days"))
            compose.onNode(hasText("Annual leave") and hasClickAction()).performClick()
            waitFor("Request cancellation")
            compose.waitUntil {
                compose
                    .onAllNodes(
                        hasText("Request cancellation") and hasClickAction() and isEnabled()
                    )
                    .fetchSemanticsNodes()
                    .size == 1
            }
            compose.onNode(hasText("Request cancellation") and hasClickAction()).performClick()
            val confirm =
                hasText("Request cancellation") and hasClickAction() and hasAnyAncestor(isDialog())
            compose.onNode(confirm).performScrollTo().performClick()
            waitFor("Enter a reason.")
            compose
                .onNodeWithContentDescription("Reason")
                .performTextInput("Plans changed\nKeep the original dates for now")
            server.leave.actions.loseNextResponse = true
            compose.onNode(confirm).performScrollTo().performClick()
            waitFor("Delivery is unconfirmed. Retry to check the same request.")
            compose.onNode(hasText("Approved") and hasText("Annual leave")).assertExists()
            assertEquals(1, server.leave.actions.requests.size)
            capture("leave-cancellation-unconfirmed")
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Leave balances")
            // The offline step targets the detail read, after the overview has resumed.
            compose.waitUntil {
                compose
                    .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                    .fetchSemanticsNodes()
                    .size == 1
            }
            server.leave.offline = true
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("1.5 days"))
            compose.onNode(hasText("Annual leave") and hasClickAction()).performClick()
            waitFor("Delivery is unconfirmed. Retry to check the same request.")
            server.leave.offline = false
            compose.waitUntil {
                compose
                    .onAllNodes(hasText("Retry delivery") and isEnabled())
                    .fetchSemanticsNodes()
                    .size == 1
            }
            compose.onNodeWithText("Retry delivery").performClick()
            waitFor("Withdraw cancellation")
            compose.waitUntil {
                compose
                    .onAllNodes(
                        hasText("Withdraw cancellation") and hasClickAction() and isEnabled()
                    )
                    .fetchSemanticsNodes()
                    .size == 1
            }
            compose
                .onNode(hasText("Cancellation pending") and hasText("Annual leave"))
                .assertExists()
            assertEquals(2, server.leave.actions.requests.size)
            assertEquals(server.leave.actions.requests[0], server.leave.actions.requests[1])
            capture("leave-cancellation-pending")
            compose.onNode(hasText("Withdraw cancellation") and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("Reason").performTextInput("Keep approved dates")
            compose
                .onNode(
                    hasText("Withdraw cancellation") and
                        hasClickAction() and
                        hasAnyAncestor(isDialog())
                )
                .performScrollTo()
                .performClick()
            waitFor("Request cancellation")
            compose.onNode(hasText("Approved") and hasText("Annual leave")).assertExists()
            assertEquals(3, server.leave.actions.requests.size)
            assertNotEquals(
                server.leave.actions.requests[0].first,
                server.leave.actions.requests[2].first,
            )
            capture("leave-cancellation-withdrawn")
        }
    }

    @Test
    fun homeMenuDoesNotMoveBetweenSuccessfulAcquisitionAndObservedPublication() {
        val response = CompletableDeferred<Unit>()
        val publication = CompletableDeferred<Unit>()
        val server =
            NativeUiServer().apply {
                workforce.attendanceGate = response
                workforce.asOf = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Jakarta"))
            }
        val cache = NativeWorkforceReplicaUi().apply { publicationGate = publication }
        try {
            withApplication(server, module { single<WorkforceReplicaRepository> { cache } }) {
                signIn()
                waitFor("Choose a company")
                compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
                waitFor("Welcome")
                compose.waitUntil { server.workforce.attendanceActive.get() == 1 }
                val menu = compose.onNode(hasText("Work calendar") and hasClickAction())
                val pending = menu.fetchSemanticsNode().boundsInRoot
                response.complete(Unit)
                compose.waitUntil { cache.hasSnapshot() }
                compose.waitUntil {
                    compose
                        .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                        .fetchSemanticsNodes()
                        .size == 1
                }
                val published = menu.fetchSemanticsNode().boundsInRoot
                assertEquals(
                    pending.top,
                    published.top,
                    "A pending cache notification must not collapse Home's capture card",
                )
                compose.onNode(hasText("Clock in") and hasClickAction()).assertIsNotEnabled()
                publication.complete(Unit)
                compose.waitUntil {
                    compose
                        .onAllNodes(hasText("Clock in") and hasClickAction() and isEnabled())
                        .fetchSemanticsNodes()
                        .size == 1
                }
                assertEquals(pending.top, menu.fetchSemanticsNode().boundsInRoot.top)
                menu.performClick()
                waitFor("Night shift")
            }
        } finally {
            response.complete(Unit)
            publication.complete(Unit)
        }
    }

    @Test
    fun calendarReadDenialRemainsAuthoritativeWhenCleanupFailsAndRetryIsPending() {
        val server = NativeUiServer()
        val cache = NativeWorkforceReplicaUi()
        val release = CompletableDeferred<Unit>()
        try {
            withApplication(server, module { single<WorkforceReplicaRepository> { cache } }) {
                signIn()
                waitFor("Choose a company")
                compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
                waitFor("Work calendar")
                compose.onNodeWithText("Work calendar").performScrollTo().performClick()
                waitFor("Night shift")
                refreshWhenReady {
                    cache.rejectClear = true
                    server.workforce.failureCode = "mfa_required"
                }
                waitFor("Verify your account")
                compose.waitUntil { cache.subscribers() == 0 }
                // The shared read gate can report denial before acquisition finishes.
                refreshWhenReady {
                    server.workforce.failureCode = null
                    server.workforce.calendarGate = release
                    cache.rejectClear = false
                }
                compose.waitUntil { server.workforce.active.get() == 1 }
                compose.onNodeWithText("Night shift").assertDoesNotExist()
                compose.onNodeWithText("Verify your account").assertExists()
                assertEquals(0, cache.subscribers())
                release.complete(Unit)
                waitFor("Night shift")
                compose.onNodeWithText("Verify your account").assertDoesNotExist()
                compose.waitUntil { cache.subscribers() == 1 }
                compose.onNodeWithContentDescription("Back").performClick()
                waitFor("Welcome")
                waitFor("Clock in")
                compose.waitUntil { cache.subscribers() == 1 }
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun calendarRetainsStaleDataDuringExplicitRefresh() {
        val server = NativeUiServer()
        val cache = NativeWorkforceReplicaUi()
        val release = CompletableDeferred<Unit>()
        try {
            withApplication(server, module { single<WorkforceReplicaRepository> { cache } }) {
                signIn()
                waitFor("Choose a company")
                compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
                waitFor("Work calendar")
                compose.onNodeWithText("Work calendar").performScrollTo().performClick()
                waitFor("Night shift")
                refreshWhenReady {
                    server.workforce.calendarGate = release
                    cache.markStale()
                    compose.onNodeWithText("Night shift").assertExists()
                }
                compose.waitUntil { server.workforce.active.get() == 1 }
                assertEquals(2, cache.subscribers())
                compose.onNodeWithText("Connect to refresh your schedule.").assertDoesNotExist()
                release.complete(Unit)
                waitFor("Night shift")
                compose.onNodeWithText("Connect to refresh your schedule.").assertDoesNotExist()
                compose.waitUntil { cache.subscribers() == 2 }
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun leaveFiltersQuerySavedRecordsOfflineWithoutCrossingCompanyScope() {
        val server =
            NativeUiServer().apply {
                leave.enabled = true
                companyPolicy.disabledModules = setOf("WORKFORCE", "PEOPLE")
            }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Leave") and hasClickAction()).performScrollTo().performClick()
            waitFor("12.5 days")
            compose.onNodeWithText("Leave balances").assertExists()
            compose.waitUntil {
                compose
                    .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                    .fetchSemanticsNodes()
                    .size == 1
            }
            val initialReads = server.leave.syncRequests.size
            capture("leave-balances")
            compose.onNodeWithContentDescription("Previous year").performClick()
            waitFor("No leave balance for this year.")
            assertTrue(server.leave.years.isEmpty())
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("All statuses"))
            compose.onNode(hasText("All statuses") and hasClickAction()).performClick()
            waitFor("Request status")
            compose.onNode(hasText("Approved") and isSelectable()).performClick()
            assertTrue(server.leave.statuses.isEmpty())
            assertEquals(initialReads, server.leave.syncRequests.size)
            waitFor("1.5 days")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("1.5 days"))
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            capture("leave-filtered-history")
            server.leave.offline = true
            refreshWhenReady()
            waitFor("Unable to connect. Check your connection.")
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Leave") and hasClickAction()).performScrollTo().performClick()
            waitFor("12.5 days")
            compose.onNodeWithText("2026").assertExists()
            compose.onNodeWithContentDescription("Previous year").performClick()
            waitFor("No leave balance for this year.")
            compose.onNodeWithText("2025").assertExists()
            server.leave.offline = false
            val reads = server.leave.syncRequests.size
            refreshWhenReady()
            compose.waitUntil { server.leave.syncRequests.size > reads }
            compose.waitUntil {
                !compose
                    .onNodeWithContentDescription("Refresh")
                    .fetchSemanticsNode()
                    .config
                    .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
            compose.onNodeWithText("2025").assertExists()
            assertTrue(server.leave.years.isEmpty())
            assertTrue(server.leave.statuses.isEmpty())
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Account") and isSelectable()).performClick()
            compose.onNodeWithContentDescription("Change company").performClick()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Lintas Karya") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Leave") and hasClickAction()).performScrollTo().performClick()
            waitFor("Employment is not linked to this account.")
            compose.onNodeWithText("12.5 days").assertDoesNotExist()
            compose.onNodeWithText("Annual leave").assertDoesNotExist()
        }
    }

    @Test
    fun leaveRequestDetailsRestoreOfflineAndKeepCurrentMetadataWithEarlierHistory() {
        val server = NativeUiServer().apply { leave.enabled = true }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Leave") and hasClickAction()).performScrollTo().performClick()
            waitFor("12.5 days")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("1.5 days"))
            compose.onNode(hasText("Annual leave") and hasClickAction()).performClick()
            waitFor("Leave request")
            waitFor("Family appointment")
            compose.onNode(hasText("Approved") and hasText("Annual leave")).assertExists()
            capture("leave-request-details")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Earlier updates"))
            compose.onNodeWithText("Earlier updates").performClick()
            waitFor("Review 2 completed")
            waitFor("Family appointment")
            compose.onNode(hasText("Approved") and hasText("Annual leave")).assertExists()
            server.leave.offline = true
            refreshWhenReady()
            waitFor("Unable to connect. Check your connection.")
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Leave balances")
            waitFor("12.5 days")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("1.5 days"))
            compose.onNode(hasText("Annual leave") and hasClickAction()).performClick()
            waitFor("Leave request")
            waitFor("Family appointment")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Earlier updates"))
            compose.onNodeWithText("Earlier updates").performClick()
            waitFor("Review 2 completed")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Latest updates"))
            compose.onNodeWithText("Latest updates").performClick()
            waitFor("Family appointment")
            server.leave.offline = false
            server.leave.rejectDetails = true
            refreshWhenReady()
            waitFor("This leave request is unavailable.")
            compose.onNodeWithText("Family appointment").assertDoesNotExist()
            server.leave.offline = true
            refreshWhenReady()
            waitFor("This leave request is unavailable.")
            compose.onNodeWithText("Family appointment").assertDoesNotExist()
            server.leave.offline = false
            server.leave.rejectDetails = false
            refreshWhenReady()
            waitFor("Family appointment")
        }
    }

    @Test
    fun attendanceShowsMonthlyFactsAndDetailsWithAnOfflineDateFilter() {
        val server = NativeUiServer().apply { workforce.historyEvidence = true }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose
                .onNode(hasText("Attendance") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("October 2026")
            compose.onNode(hasText("Clock in") and hasClickAction()).assertDoesNotExist()
            compose.onNode(hasText("Clock out") and hasClickAction()).assertDoesNotExist()
            compose.onNode(hasText("Present") and hasText("1")).assertExists()
            compose.onNode(hasText("Absent") and hasText("1")).assertExists()
            compose.onNode(hasText("Needs review") and hasText("1")).assertExists()
            compose.onNode(hasText("Verified time") and hasText("8 h 0 min")).assertExists()
            compose.onNodeWithContentDescription("Next month").assertIsNotEnabled()
            capture("attendance-monthly")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Sat, 3 Oct 2026"))
            compose.onNode(hasText("Sat, 3 Oct 2026") and hasClickAction()).performClick()
            waitFor("Clock in: 09:00")
            compose.onNodeWithText("Clock out: 17:00").assertExists()
            compose.onNodeWithContentDescription("Close").performClick()
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.onNodeWithText("October 2026").performClick()
            waitFor("Choose month")
            compose.onNodeWithText("December").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Next year").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Previous year").performClick()
            compose.onNodeWithContentDescription("Previous year").performClick()
            waitFor("2024")
            compose.onNodeWithText("February").performClick()
            waitFor("February 2024")
            compose.waitUntil {
                compose
                    .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.waitUntil(10_000) {
                compose
                    .onAllNodes(hasText("Present") and hasText("1"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            server.workforce.offline = true
            refreshWhenReady()
            waitFor("Unable to connect. Check your connection.")
            compose.onNodeWithText("February 2024").assertExists()
            compose.onNode(hasText("Present") and hasText("1")).assertExists()
            capture("attendance-monthly-offline")
            val reloads = server.workforce.syncReads.get()
            server.workforce.offline = false
            refreshWhenReady()
            compose.waitUntil { server.workforce.syncReads.get() > reloads }
            compose.onNodeWithText("February 2024").assertExists()
            compose.runOnIdle { backDispatcher.pressBack() }
            waitFor("Welcome")
            server.workforce.offline = true
            compose
                .onNode(hasText("Attendance") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("October 2026")
            compose.onNode(hasText("Present") and hasText("1")).assertExists()
            compose.runOnIdle { backDispatcher.pressBack() }
            waitFor("Welcome")
            server.workforce.offline = false
            compose.onNode(hasText("Account") and isSelectable()).performClick()
            compose.onNodeWithContentDescription("Change company").performClick()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Lintas Karya") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose
                .onNode(hasText("Attendance") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Employment is not linked to this account.")
            compose.onNodeWithText("February 2024").assertDoesNotExist()
            compose.onNodeWithText("Present").assertDoesNotExist()
        }
    }

    @Test
    fun attendanceHistoryKeepsStaleDataButHonorsConfirmedAccessDenial() {
        val cache = NativeWorkforceReplicaUi()
        val server = NativeUiServer()
        val release = CompletableDeferred<Unit>()
        try {
            withApplication(server, module { single<WorkforceReplicaRepository> { cache } }) {
                signIn()
                waitFor("Choose a company")
                compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
                waitFor("Welcome")
                compose
                    .onNode(hasText("Attendance") and hasClickAction())
                    .performScrollTo()
                    .performClick()
                waitFor("Present")
                compose.waitUntil { server.workforce.attendanceActive.get() == 0 }
                cache.markStale()
                compose.onNodeWithText("Present").assertExists()
                refreshWhenReady { server.workforce.attendanceGate = release }
                compose.waitUntil { server.workforce.attendanceActive.get() == 1 }
                assertEquals(2, cache.subscribers())
                compose
                    .onNodeWithText("Refresh attendance before recording a new entry.")
                    .assertDoesNotExist()
                compose.onNodeWithText("Present").assertExists()
                assertEquals(2, cache.subscribers())
                server.workforce.attendanceGate = null
                release.complete(Unit)
                waitFor("Present")
                compose
                    .onNodeWithText("Refresh attendance before recording a new entry.")
                    .assertDoesNotExist()
                compose.waitUntil { cache.subscribers() == 2 }
                server.workforce.failureCode = "mfa_required"
                refreshWhenReady()
                waitFor("Verify your account")
                compose.onNodeWithText("Present").assertDoesNotExist()
                compose.waitUntil { cache.subscribers() == 0 }
                server.workforce.failureCode = null
                server.workforce.offline = true
                refreshWhenReady()
                waitFor("Unable to connect. Check your connection.")
                compose.onNodeWithText("Verify your account").assertExists()
                compose.onNodeWithText("Present").assertDoesNotExist()
                assertEquals(0, cache.subscribers())
                server.workforce.offline = false
                refreshWhenReady()
                waitFor("Present")
                compose.onNodeWithText("Verify your account").assertDoesNotExist()
                compose.waitUntil { cache.subscribers() == 1 }
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun homeAttendanceDoesNotReopenDeniedCacheOnFailedRetry() {
        val cache = NativeWorkforceReplicaUi()
        val server = NativeUiServer()
        withApplication(server, module { single<WorkforceReplicaRepository> { cache } }) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Clock in")
            compose.waitUntil { server.workforce.attendanceActive.get() == 0 }
            cache.rejectClear = true
            server.workforce.failureCode = "mfa_required"
            refreshWhenReady()
            waitFor("Verify your account")
            compose.waitUntil { cache.subscribers() == 0 }
            server.workforce.failureCode = null
            server.workforce.offline = true
            refreshWhenReady()
            waitFor("Unable to connect. Check your connection.")
            compose.onNodeWithText("Verify your account").assertExists()
            compose.onNode(hasText("Clock in") and hasClickAction()).assertDoesNotExist()
            assertEquals(0, cache.subscribers())
            server.workforce.offline = false
            cache.rejectClear = false
            refreshWhenReady()
            waitFor("Clock in")
            compose.waitUntil { cache.subscribers() == 1 }
        }
    }

    @Test
    fun changingTheLocalMonthKeepsOneSyncAndLeavingThePageCancelsIt() {
        val server = NativeUiServer()
        val release = CompletableDeferred<Unit>()
        try {
            withApplication(server) {
                signIn()
                waitFor("Choose a company")
                compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
                waitFor("Welcome")
                compose
                    .onNode(hasText("Attendance") and hasClickAction())
                    .performScrollTo()
                    .performClick()
                waitFor("October 2026")
                compose.waitUntil { server.workforce.attendanceActive.get() == 0 }
                refreshWhenReady { server.workforce.attendanceGate = release }
                compose.waitUntil { server.workforce.attendanceActive.get() == 1 }
                val requests = server.workforce.syncReads.get()
                val cancelled = server.workforce.attendanceCancelled.get()
                compose.onNodeWithContentDescription("Previous month").performClick()
                waitFor("September 2026")
                compose.onNodeWithContentDescription("Previous month").performClick()
                waitFor("August 2026")
                assertEquals(requests, server.workforce.syncReads.get())
                assertEquals(cancelled, server.workforce.attendanceCancelled.get())
                server.workforce.attendanceGate = null
                compose.runOnIdle { backDispatcher.pressBack() }
                waitFor("Welcome")
                compose.waitUntil { server.workforce.attendanceCancelled.get() == cancelled + 1 }
                release.complete(Unit)
                compose.waitUntil { server.workforce.attendanceActive.get() == 0 }
                compose.onNodeWithText("August 2026").assertDoesNotExist()
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun inboxBadgeUsesTheFullTotalUpdatesAfterReadingAndClearsOnCompanyChange() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("36 unread").fetchSemanticsNodes().size ==
                    1
            }
            compose.onNode(hasText("Inbox") and isSelectable()).performClick()
            waitFor("Office closure")
            compose.onNodeWithContentDescription("36 unread").assertExists()
            capture("inbox-unread")
            compose.onNodeWithText("Office closure").performClick()
            waitFor(server.inbox.firstBody)
            compose.onNodeWithText("Mark as read").performScrollTo().performClick()
            compose.waitUntil(10_000) { server.inbox.actions.size == 1 }
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Office closure")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("35 unread").fetchSemanticsNodes().size ==
                    1
            }
            compose.onNode(hasText("Account") and isSelectable()).performClick()
            compose.onNodeWithContentDescription("Change company").performClick()
            waitFor("Choose a company")
            compose.onNodeWithContentDescription("35 unread").assertDoesNotExist()
            compose.onNode(hasText("PT Lintas Karya") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.waitUntil {
                server.inbox.countCompanies.lastOrNull() == NativeUiServer.COMPANY_TWO
            }
            compose.onNodeWithContentDescription("35 unread").assertDoesNotExist()
            compose.onNodeWithContentDescription("0 unread").assertDoesNotExist()
        }
    }

    @Test
    fun openingAnInnerRouteCancelsThePendingBadgeAndReturningReloadsIt() {
        val server = NativeUiServer().apply { inbox.holdNextCount = true }
        try {
            withApplication(server) {
                signIn()
                waitFor("Choose a company")
                compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
                waitFor("Welcome")
                compose.waitUntil { server.inbox.countStarted.isCompleted }
                compose.onNode(hasText("Account") and isSelectable()).performClick()
                compose.onNodeWithText("Settings").performScrollTo().performClick()
                waitFor("Bahasa Indonesia")
                compose.waitUntil { server.inbox.countCancelled.isCompleted }
                server.inbox.countReleased.complete(Unit)
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitUntil(10_000) {
                    compose
                        .onAllNodesWithContentDescription("36 unread")
                        .fetchSemanticsNodes()
                        .size == 1
                }
                assertEquals(2, server.inbox.countCompanies.size)
            }
        } finally {
            server.inbox.countReleased.complete(Unit)
        }
    }

    @Test
    fun rootTabsRetainContentAndScrollWithoutRepeatingSuccessfulRequests() {
        val cache = NativeWorkforceReplicaUi()
        val server =
            NativeUiServer().apply {
                workforce.asOf = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Jakarta"))
            }
        withApplication(server, module { single<WorkforceReplicaRepository> { cache } }) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            compose.waitUntil {
                compose
                    .onAllNodes(hasText("Clock in") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val attendanceRequests = server.workforce.syncReads.get()
            compose.onNode(hasText("Employees") and isSelectable()).performClick()
            waitFor("Sam Wilson")
            waitFor("Colleague 03")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Colleague 15"))
            val row = compose.onNodeWithText("Colleague 15").fetchSemanticsNode().boundsInRoot
            val directoryRequests = server.directory.queries.size
            repeat(3) {
                compose.onNode(hasText("Home") and isSelectable()).performClick()
                compose.onNode(hasText("Clock in") and hasClickAction()).assertIsEnabled()
                compose.onNodeWithContentDescription("Refresh").assertIsEnabled()
                compose.onNode(hasText("Account") and isSelectable()).performClick()
                compose.onNode(hasText("Employees") and isSelectable()).performClick()
                compose.onNodeWithText("Colleague 15").assertIsDisplayed()
                compose.onNodeWithContentDescription("Refresh").assertIsEnabled()
                val restored =
                    compose.onNodeWithText("Colleague 15").fetchSemanticsNode().boundsInRoot
                assertTrue(kotlin.math.abs(row.top - restored.top) <= 1f)
                compose.runOnIdle {
                    assertEquals(attendanceRequests, server.workforce.syncReads.get())
                    assertEquals(directoryRequests, server.directory.queries.size)
                }
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            compose
                .onNodeWithContentDescription("Search employees")
                .assert(hasSetTextAction().not())
            refreshWhenReady()
            compose.waitUntil { server.directory.queries.size == directoryRequests + 1 }
            waitFor("Colleague 03")
            compose.onNode(hasText("Home") and isSelectable()).performClick()
            refreshWhenReady()
            compose.waitUntil { server.workforce.syncReads.get() == attendanceRequests + 1 }
        }
        compose.waitUntil { cache.subscribers() == 0 }
    }

    @Test
    fun hiddenHomeRetainsStaleDataWithoutFetchingAndOfflineRefreshKeepsItVisible() {
        val cache = NativeWorkforceReplicaUi()
        val server = NativeUiServer()
        withApplication(server, module { single<WorkforceReplicaRepository> { cache } }) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Clock in")
            compose.waitUntil { cache.hasSnapshot() }
            compose.onNode(hasText("Employees") and isSelectable()).performClick()
            waitFor("Sam Wilson")
            assertEquals(1, cache.subscribers())
            val requestsBeforeExpiry = server.workforce.syncReads.get()
            cache.markStale()
            assertEquals(1, cache.subscribers())
            assertEquals(requestsBeforeExpiry, server.workforce.syncReads.get())
            server.workforce.offline = true
            compose.onNode(hasText("Home") and isSelectable()).performClick()
            waitFor("Clock in")
            assertEquals(requestsBeforeExpiry, server.workforce.syncReads.get())
            refreshWhenReady()
            waitFor("Unable to connect. Check your connection.")
            compose.onNode(hasText("Clock in") and hasClickAction()).assertExists()
            assertTrue(cache.hasSnapshot())
            assertEquals(1, cache.subscribers())
            server.workforce.offline = false
            refreshWhenReady()
            waitFor("Clock in")
            compose.waitUntil { cache.subscribers() == 1 }
        }
        compose.waitUntil { cache.subscribers() == 0 }
    }

    @Test
    fun hiddenDirectoryObservesAccessChangesWithoutFetchingOrLosingItsOwner() {
        val server = NativeUiServer()
        withApplication(server) { koin ->
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Employees") and isSelectable()).performClick()
            waitFor("Sam Wilson")
            val reads = server.directory.queries.size
            val workspaces = koin.get<WorkspaceRepository>()
            val selected = runBlocking {
                val session =
                    assertNotNull(
                        assertIs<AppResult.Success<Session?>>(
                                koin.get<SessionRepository>().current()
                            )
                            .value
                    )
                assertNotNull(
                    assertIs<AppResult.Success<Workspace?>>(workspaces.current(session.id)).value
                )
            }
            val renewed =
                selected.copy(
                    admission =
                        selected.admission.copy(
                            checkedAt = selected.admission.checkedAt + 1.milliseconds
                        )
                )
            runBlocking {
                assertEquals(
                    AppResult.Success(true),
                    workspaces.compareAndSet(selected.sessionId, selected, renewed),
                )
            }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("Sam Wilson").assertExists()
            assertEquals(reads, server.directory.queries.size)

            compose.onNode(hasText("Account") and isSelectable()).performClick()
            val denied = renewed.copy(permissions = renewed.permissions - "people.directory.read")
            runBlocking {
                assertEquals(
                    AppResult.Success(true),
                    workspaces.compareAndSet(selected.sessionId, renewed, denied),
                )
            }
            compose.mainClock.advanceTimeBy(500)
            assertEquals(reads, server.directory.queries.size)
            compose.onNode(hasText("Employees") and isSelectable()).performClick()
            waitFor("Your account cannot perform this action.")
            compose.onNodeWithText("Sam Wilson").assertDoesNotExist()
            assertEquals(reads, server.directory.queries.size)
            runBlocking {
                assertEquals(
                    AppResult.Success(true),
                    workspaces.compareAndSet(selected.sessionId, denied, renewed),
                )
            }
            waitFor("Sam Wilson")
            assertEquals(reads, server.directory.queries.size)
        }
    }

    @Test
    fun returningToEmployeesResumesCancelledRefreshAndKeepsTheLoadedRows() {
        val server = NativeUiServer()
        try {
            withApplication(server) {
                signIn()
                waitFor("Choose a company")
                compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
                waitFor("Welcome")
                compose.onNode(hasText("Employees") and isSelectable()).performClick()
                waitFor("Sam Wilson")
                val reads = server.directory.queries.size
                server.directory.holdNext = true
                refreshWhenReady()
                compose.waitUntil { server.directory.started.isCompleted }
                compose.onNodeWithText("Sam Wilson").assertIsDisplayed()
                compose.onNode(hasText("Home") and isSelectable()).performClick()
                compose.waitUntil { server.directory.cancelled.isCompleted }
                server.directory.released.complete(Unit)
                assertEquals(reads + 1, server.directory.queries.size)
                compose.onNode(hasText("Employees") and isSelectable()).performClick()
                compose.onNodeWithText("Sam Wilson").assertIsDisplayed()
                compose.waitUntil { server.directory.queries.size == reads + 2 }
                compose.waitUntil {
                    compose
                        .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                        .fetchSemanticsNodes()
                        .size == 1
                }
                compose.onNodeWithText("Sam Wilson").assertIsDisplayed()
            }
        } finally {
            server.directory.released.complete(Unit)
        }
    }

    @Test
    fun employeeSearchReadsLocallyAndReturnsToTheRetainedDirectoryWithoutCrossingCompanies() {
        val server = NativeUiServer()
        val owners = mutableListOf<EmployeesViewModel>()
        val definitions = module {
            factory<EmployeesViewModel> { parameters ->
                EmployeesViewModel(parameters.get(), get(), get()).also(owners::add)
            }
        }
        try {
            withApplication(server, definitions) {
                signIn()
                waitFor("Choose a company")
                compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
                waitFor("Welcome")
                compose.onNode(hasText("Employees") and isSelectable()).performClick()
                waitFor("Sam Wilson")
                val directory = owners.single()
                compose.onNodeWithContentDescription("Back").assertDoesNotExist()
                compose.onNodeWithText("HR Specialist").assertExists()
                capture("employees")
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Next page"))
                compose.onNodeWithText("Next page").performClick()
                waitFor("Colleague 21")
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("First page"))
                compose.onNodeWithText("First page").performClick()
                waitFor("Sam Wilson")
                openEmployeeSearch()
                val search = owners.last()
                assertNotSame(directory, search)
                compose.onNode(hasText("Account") and isSelectable()).assertDoesNotExist()
                compose
                    .onNodeWithContentDescription("Search employees")
                    .performTextReplacement("Sam")
                waitFor("Sam Wilson")
                compose.onNodeWithText("Alex Morgan").assertDoesNotExist()
                compose.onNodeWithText("Next page").assertDoesNotExist()
                val beforeReturn = server.directory.queries.size
                server.directory.offline = true
                compose
                    .onNodeWithContentDescription("Search employees")
                    .performTextReplacement("Alex")
                compose.onNodeWithContentDescription("Back").performClick()
                waitFor("Sam Wilson")
                compose.runOnIdle {
                    assertFalse(search.viewModelScope.coroutineContext.job.isActive)
                    assertTrue(directory.viewModelScope.coroutineContext.job.isActive)
                    assertEquals(beforeReturn, server.directory.queries.size)
                }
                compose
                    .onNodeWithContentDescription("Search employees")
                    .assert(hasSetTextAction().not())
                openEmployeeSearch()
                compose
                    .onNodeWithContentDescription("Search employees")
                    .assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.EditableText,
                            AnnotatedString(""),
                        )
                    )
                compose
                    .onNodeWithContentDescription("Search employees")
                    .performTextReplacement("Alex")
                waitFor("Alex Morgan")
                compose.onNodeWithText("Sam Wilson").assertDoesNotExist()
                capture("employee-search")
                compose.onNodeWithContentDescription("Back").performClick()
                waitFor("Sam Wilson")
                compose.runOnIdle { backDispatcher.pressBack() }
                waitFor("Welcome")
                compose.onNode(hasText("Home") and isSelectable()).assertIsSelected()
                compose.onNodeWithContentDescription("Change company").assertDoesNotExist()
                compose.onNode(hasText("Account") and isSelectable()).performClick()
                server.directory.offline = false
                compose.onNodeWithContentDescription("Change company").performClick()
                waitFor("Choose a company")
                compose.onNode(hasText("PT Lintas Karya") and hasClickAction()).performClick()
                waitFor("Welcome")
                compose.onNode(hasText("Employees") and isSelectable()).performClick()
                waitFor("No employees found.")
                openEmployeeSearch()
                compose
                    .onNodeWithContentDescription("Search employees")
                    .assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.EditableText,
                            AnnotatedString(""),
                        )
                    )
                waitFor("No employees found.")
                compose.onNodeWithText("Sam Wilson").assertDoesNotExist()
                compose.runOnIdle {
                    assertFalse(directory.viewModelScope.coroutineContext.job.isActive)
                }
            }
            compose.runOnIdle {
                assertTrue(owners.all { !it.viewModelScope.coroutineContext.job.isActive })
            }
        } finally {
            server.directory.released.complete(Unit)
        }
    }

    @Test
    fun departmentSelectionFiltersEveryPageAndPersistsAcrossTabs() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Employees") and isSelectable()).performClick()
            waitFor("Sam Wilson")
            compose.onNodeWithText("All departments").performClick()
            waitFor("Search departments")
            compose.waitUntil {
                compose
                    .onAllNodes(hasText("Engineering") and isSelectable())
                    .fetchSemanticsNodes()
                    .size == 1
            }
            compose.onNode(hasText("Engineering") and isSelectable()).performClick()
            waitFor("Alex Morgan")
            compose.onNodeWithText("Sam Wilson").assertDoesNotExist()
            capture("employees-department")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Next page"))
            compose.onNodeWithText("Next page").performClick()
            waitFor("Colleague 22")
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            openEmployeeSearch()
            compose.onNodeWithText("All departments").assertExists()
            compose
                .onNodeWithContentDescription("Search employees")
                .performTextReplacement("Colleague 25")
            waitFor("Colleague 25")
            compose.onNodeWithText("Next page").assertDoesNotExist()
            compose.onNodeWithContentDescription("Clear search").performClick()
            waitFor("Sam Wilson")
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Colleague 22")
            compose.onNodeWithText("Sam Wilson").assertDoesNotExist()
            compose.onNode(hasText("Account") and isSelectable()).performClick()
            compose.onNode(hasText("Employees") and isSelectable()).performClick()
            waitFor("Colleague 22")
            compose.onNode(hasText("Engineering") and hasClickAction()).performClick()
            compose.onNodeWithText("All departments").performClick()
            waitFor("Sam Wilson")
        }
    }

    @Test
    fun departmentSearchAndDismissalUseLocalDataWhileOffline() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Employees") and isSelectable()).performClick()
            waitFor("Sam Wilson")
            val requests = server.directory.queries.size
            server.directory.offline = true
            compose.onNodeWithText("All departments").performClick()
            waitFor("Search departments")
            compose
                .onNodeWithContentDescription("Search departments")
                .performTextReplacement("People")
            compose.onNodeWithContentDescription("Close").performClick()
            compose.onNodeWithContentDescription("Search departments").assertDoesNotExist()
            compose.onNodeWithText("Sam Wilson").assertExists()
            compose.onNodeWithText("All departments").performClick()
            waitFor("Search departments")
            compose.waitUntil {
                compose
                    .onAllNodes(hasText("People") and isSelectable())
                    .fetchSemanticsNodes()
                    .size == 1
            }
            compose.onNode(hasText("People") and isSelectable()).performClick()
            waitFor("Sam Wilson")
            compose.onNodeWithText("Alex Morgan").assertDoesNotExist()
            assertEquals(requests, server.directory.queries.size)
        }
    }

    @Test
    fun employeesRetainThePageOfflineButClearItAfterAccessIsDenied() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Employees") and isSelectable()).performClick()
            waitFor("Sam Wilson")
            server.directory.offline = true
            refreshWhenReady()
            waitFor("Showing the last loaded page.")
            compose.onNodeWithText("Sam Wilson").assertExists()
            server.directory.offline = false
            server.directory.denied = true
            refreshWhenReady()
            compose.waitUntil {
                compose.onAllNodesWithText("Sam Wilson").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("Showing the last loaded page.").assertDoesNotExist()
            compose.onNodeWithText("Alex Morgan").assertDoesNotExist()
            server.directory.denied = false
            refreshWhenReady()
            waitFor("Sam Wilson")
            compose.runOnIdle { backDispatcher.pressBack() }
            waitFor("Welcome")
            compose.onNode(hasText("Employees") and isSelectable()).performClick()
            waitFor("Sam Wilson")
            compose.onNodeWithContentDescription("Back").assertDoesNotExist()
            compose.onNode(hasText("Home") and isSelectable()).assertExists()
        }
    }

    @Test
    fun inboxTabStopsItsRefreshOnTabChangeAndDetailsReturnToTheSameTab() {
        val server = NativeUiServer().apply { inbox.holdNextRefresh = true }
        try {
            withApplication(server) {
                signIn()
                waitFor("Choose a company")
                compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
                waitFor("Welcome")
                compose.onNode(hasText("Inbox") and isSelectable()).performClick()
                compose.waitUntil { server.inbox.refreshStarted.isCompleted }
                compose.onNodeWithContentDescription("Back").assertDoesNotExist()
                compose.onNode(hasText("Account") and isSelectable()).performClick()
                compose.waitUntil { server.inbox.refreshCancelled.isCompleted }
                server.inbox.refreshReleased.complete(Unit)
                compose.onNode(hasText("Inbox") and isSelectable()).performClick()
                waitFor("Office closure")
                compose.onNodeWithText("Office closure").performClick()
                waitFor(server.inbox.firstBody)
                compose.runOnIdle { backDispatcher.pressBack() }
                waitFor("Office closure")
                compose.onNode(hasText("Inbox") and isSelectable()).assertIsSelected()
                compose.onNodeWithContentDescription("Back").assertDoesNotExist()
                compose.runOnIdle { backDispatcher.pressBack() }
                waitFor("Welcome")
                compose.onNode(hasText("Home") and isSelectable()).assertIsSelected()
            }
        } finally {
            server.inbox.refreshReleased.complete(Unit)
        }
    }

    @Test
    fun systemBackPopsInnerPagesThenReturnsToHomeBeforeLeavingTheApp() {
        withApplication(NativeUiServer()) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNodeWithContentDescription("Change company").assertDoesNotExist()
            compose.onNode(hasText("Services") and hasClickAction()).assertDoesNotExist()
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.waitUntil { backDispatcher.canHandleBack }
            compose.runOnIdle { backDispatcher.cancelBack() }
            compose.onNode(hasText("Account") and hasClickAction()).assertIsSelected()
            compose.onNodeWithContentDescription("Change company").assertIsDisplayed()
            compose.onNodeWithText("Settings").performScrollTo().performClick()
            waitFor("English")
            compose.waitUntil { backDispatcher.canHandleBack }
            compose.runOnIdle { backDispatcher.pressBack() }
            compose.waitUntil {
                compose
                    .onAllNodes(hasText("Account") and isSelected())
                    .fetchSemanticsNodes()
                    .size == 1
            }
            compose.runOnIdle { backDispatcher.pressBack() }
            waitFor("Welcome")
            compose.onNode(hasText("Home") and hasClickAction()).assertIsSelected()
            compose.onNodeWithContentDescription("Change company").assertDoesNotExist()
            compose.waitUntil { !backDispatcher.canHandleBack }
            compose.runOnIdle { backDispatcher.pressBack() }
            assertEquals(1, backDispatcher.unhandled)
        }
    }

    @Test
    fun allServicesHasItsOwnBackStackAndStopsHomeAttendanceWhileCovered() {
        val server = NativeUiServer().apply { workforce.attendanceGate = CompletableDeferred() }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.waitUntil(5_000) { server.workforce.attendanceActive.get() == 1 }
            compose.onAllNodesWithText("Inbox").assertCountEquals(1)
            compose.onAllNodesWithText("Employees").assertCountEquals(1)
            compose.onNodeWithText("My profile").assertDoesNotExist()
            compose.onNodeWithText("All services").performScrollTo().performClick()
            compose.waitUntil {
                compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(5_000) { server.workforce.attendanceActive.get() == 0 }
            assertEquals(1, server.workforce.attendanceCancelled.get())
            compose.onNode(hasText("Home") and hasClickAction()).assertDoesNotExist()
            compose.onNode(hasText("Account") and hasClickAction()).assertDoesNotExist()
            compose.onNodeWithText("Attendance").assertExists()
            compose.onNodeWithText("Work calendar").assertExists()
            compose.onNodeWithText("Inbox").assertDoesNotExist()
            compose.onNodeWithText("Employees").assertDoesNotExist()
            compose.onNodeWithText("My profile").assertDoesNotExist()
            capture("all-services")
            server.workforce.attendanceGate = null
            compose.onNodeWithText("Work calendar").performClick()
            waitFor("Night shift")
            compose.runOnIdle { backDispatcher.pressBack() }
            waitFor("All services")
            compose.onNode(hasText("Home") and hasClickAction()).assertDoesNotExist()
            server.workforce.attendanceGate = null
            compose.runOnIdle { backDispatcher.pressBack() }
            waitFor("Welcome")
            compose.onNode(hasText("Home") and hasClickAction()).assertIsSelected()
            compose.waitUntil { !backDispatcher.canHandleBack }
        }
    }

    @Test
    fun homeAttendanceStopsPendingWorkWhenTheTabLeavesAndHandlesLocationFailureInPlace() {
        val server =
            NativeUiServer().apply {
                workforce.attendanceGate = CompletableDeferred()
                workforce.asOf = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Jakarta"))
            }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.waitUntil(5_000) { server.workforce.attendanceActive.get() == 1 }
            val pendingMenu =
                compose
                    .onNode(hasText("Attendance") and hasClickAction())
                    .fetchSemanticsNode()
                    .boundsInRoot
            compose.onNode(hasText("Clock in") and hasClickAction()).assertIsNotEnabled()
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.waitUntil(5_000) { server.workforce.attendanceActive.get() == 0 }
            assertEquals(1, server.workforce.attendanceCancelled.get())
            server.workforce.attendanceGate = null
            compose.onNode(hasText("Home") and hasClickAction()).performClick()
            compose.waitUntil(5_000) {
                compose
                    .onAllNodes(hasText("Clock in") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val capture =
                compose.onNodeWithTag("attendance-capture").fetchSemanticsNode().boundsInRoot
            val menu =
                compose
                    .onNode(hasText("Attendance") and hasClickAction())
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue(capture.bottom < menu.top)
            assertTrue(
                kotlin.math.abs(menu.top - pendingMenu.top) <= 1f,
                "Acquisition must not move the menu beneath the loading card",
            )
            compose.onNode(hasText("Clock in") and hasClickAction()).performClick()
            waitFor(
                "A current location could not be obtained. Try again outdoors or save for review."
            )
            compose.onNodeWithContentDescription("Change company").assertDoesNotExist()
            compose.onNode(hasText("Clock out") and hasClickAction()).assertIsNotEnabled()
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.onNode(hasText("Home") and hasClickAction()).performClick()
            compose.waitUntil(5_000) {
                compose
                    .onAllNodes(hasText("Clock in") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose
                .onNodeWithText(
                    "A current location could not be obtained. Try again outdoors or save for review."
                )
                .assertDoesNotExist()
        }
    }

    @Test
    fun attendanceRefreshKeepsSavedContentWhenFreshnessElapsesDuringTheRequest() {
        val server =
            NativeUiServer().apply {
                workforce.asOf = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Jakarta"))
            }
        val cache = NativeWorkforceReplicaUi()
        withApplication(server, module { single<WorkforceReplicaRepository> { cache } }) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            compose.waitUntil(5_000) {
                compose
                    .onAllNodes(hasText("Clock in") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val release = CompletableDeferred<Unit>()
            server.workforce.attendanceGate = release
            refreshWhenReady()
            compose.waitUntil(5_000) { server.workforce.attendanceActive.get() == 1 }
            cache.markStale()
            assertEquals(1, cache.subscribers())
            compose.onNode(hasText("Clock in") and hasClickAction()).assertExists()
            compose
                .onNodeWithText("Refresh attendance before recording a new entry.")
                .assertDoesNotExist()
            release.complete(Unit)
            server.workforce.attendanceGate = null
            compose.waitUntil(5_000) {
                compose
                    .onAllNodes(hasText("Clock in") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose
                .onNodeWithText("Refresh attendance before recording a new entry.")
                .assertDoesNotExist()
            server.workforce.offline = true
            refreshWhenReady()
            waitFor("Unable to connect. Check your connection.")
            server.workforce.offline = false
            refreshWhenReady()
            compose.waitUntil(5_000) {
                compose
                    .onAllNodes(hasText("Clock in") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Unable to connect. Check your connection.").assertDoesNotExist()
        }
    }

    @Test
    fun workforceCalendarKeepsItsOwnModuleAdmissionAndNavigatesCompleteWeeks() {
        val server = NativeUiServer().apply { companyPolicy.disabledModules = setOf("PEOPLE") }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNodeWithText("My profile").assertDoesNotExist()
            compose.onNodeWithText("Work calendar").performScrollTo().performClick()
            waitFor("Night shift")
            compose.onNodeWithText("22:00 – 06:00 (+1 day)").performScrollTo().assertExists()
            compose.onNodeWithText("7 h 0 min").assertExists()
            capture("work-calendar-night")
            compose.onNodeWithContentDescription("Next week").performScrollTo().performClick()
            waitFor("Mon, 19 Oct 2026 – Sun, 25 Oct 2026")
            compose.onNodeWithText("This week").performClick()
            waitFor("Mon, 12 Oct 2026 – Sun, 18 Oct 2026")
            compose.onNodeWithText("Company holiday").performScrollTo().assertExists()
            capture("work-calendar-holiday")
        }
    }

    @Test
    fun savedCalendarRemainsReadableOfflineButDoesNotAppearInAnotherCompany() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Work calendar")
            // This scenario validates persisted data; navigation gestures have separate coverage.
            compose.onNodeWithText("Work calendar").performScrollTo().performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.OnClick
            ) {
                it()
            }
            waitFor("Night shift")
            compose.waitUntil {
                compose
                    .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            server.workforce.offline = true
            refreshWhenReady()
            waitFor("Unable to connect. Check your connection.")
            compose.onNodeWithText("Night shift").performScrollTo().assertExists()
            capture("work-calendar-offline")
            compose.runOnIdle { backDispatcher.pressBack() }
            waitFor("Welcome")
            compose.onNodeWithText("Work calendar").performScrollTo().performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.OnClick
            ) {
                it()
            }
            waitFor("Night shift")
            compose.runOnIdle { backDispatcher.pressBack() }
            waitFor("Welcome")
            server.workforce.offline = false
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("Change company").performClick()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Lintas Karya") and hasClickAction()).performClick()
            waitFor("Work calendar")
            compose.onNodeWithText("Work calendar").performScrollTo().performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.OnClick
            ) {
                it()
            }
            waitFor("Employment is not linked to this account.")
            compose.onNodeWithText("Night shift").assertDoesNotExist()
        }
    }

    @Test
    fun calendarDepartureCancelsPendingAcquisitionAndMfaDenialHidesSavedContent() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Work calendar")
            compose.onNodeWithText("Work calendar").performScrollTo().performClick()
            waitFor("Night shift")
            server.workforce.holdCalendar = true
            refreshWhenReady()
            compose.waitUntil(5_000) { server.workforce.active.get() == 1 }
            server.workforce.holdCalendar = false
            compose.runOnIdle { backDispatcher.pressBack() }
            waitFor("Welcome")
            compose.waitUntil(5_000) { server.workforce.active.get() == 0 }
            assertEquals(1, server.workforce.cancelled.get())
            server.workforce.holdCalendar = false
            compose.onNodeWithText("Work calendar").performScrollTo().performClick()
            waitFor("Night shift")
            compose.waitUntil {
                compose
                    .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            server.workforce.failureCode = "mfa_required"
            refreshWhenReady()
            try {
                waitFor("Verify your account")
            } finally {
                capture("work-calendar-verification")
            }
            compose.onNodeWithText("Night shift").assertDoesNotExist()
            compose.onNodeWithText("Private diagnostic").assertDoesNotExist()
        }
    }

    @Test
    fun companyAvailabilityPreservesRecoveryAcrossUpdateMaintenanceAndOfflineRetry() {
        val server = NativeUiServer().apply { companyPolicy.minimumAndroid = 2 }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Update required")
            compose.onNodeWithText("Inbox").assertDoesNotExist()
            compose.waitUntil {
                compose
                    .onAllNodes(hasText("Retry") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            capture("company-update-required")
            server.companyPolicy.offline = true
            compose.onNodeWithText("Retry").performClick()
            try {
                waitFor("Unable to connect. Check your connection.")
            } finally {
                capture("company-policy-offline-retry")
            }
            compose.onNodeWithText("Update required").assertExists()
            compose.onNodeWithText("Welcome").assertDoesNotExist()
            server.companyPolicy.offline = false
            compose.onNodeWithText("Change company").performScrollTo().performClick()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Lintas Karya") and hasClickAction()).performClick()
            waitFor("Inbox")
            compose.onNodeWithText("PT Lintas Karya").assertExists()
            compose.onNodeWithText("Update required").assertDoesNotExist()
            server.companyPolicy.minimumAndroid = 0
            server.companyPolicy.maintenance = true
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("Change company").performClick()
            try {
                waitFor("Choose a company")
            } finally {
                capture("company-switch-after-home-refresh")
            }
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Scheduled maintenance")
            compose.onNodeWithText("Inbox").assertDoesNotExist()
            compose.waitUntil {
                compose
                    .onAllNodes(hasText("Retry") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            server.companyPolicy.maintenance = false
            compose.onNodeWithText("Retry").performClick()
            waitFor("Inbox")
            compose.onNodeWithText("Scheduled maintenance").assertDoesNotExist()
        }
    }

    @Test
    fun disabledModulesAreRemovedFromTheirCompanyWithoutAffectingAnotherCompany() {
        val server =
            NativeUiServer().apply {
                companyPolicy.disabledModules = setOf("PEOPLE", "COMMUNICATIONS")
            }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNodeWithText("All services").performScrollTo().performClick()
            compose.waitUntil {
                compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Attendance").assertExists()
            compose.onNodeWithText("My profile").assertDoesNotExist()
            compose.onNodeWithText("Inbox").assertDoesNotExist()
            capture("company-disabled-modules")
            compose.runOnIdle { backDispatcher.pressBack() }
            waitFor("Welcome")
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.onNodeWithText("My profile").assertDoesNotExist()
            compose.onNodeWithContentDescription("Change company").performClick()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Lintas Karya") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Account") and isSelectable()).performClick()
            compose.onNodeWithText("My profile").assertExists()
            assertTrue(server.inbox.actions.isEmpty())
        }
    }

    @Test
    fun explicitAcknowledgementRecoversTheSameCommandAfterResponseLossAndSignInAgain() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Inbox") and isSelectable()).performClick()
            waitFor("Office closure")
            compose.onNodeWithText("Office closure").performClick()
            waitFor("Mark as read")
            compose.onNodeWithText("Acknowledge").performScrollTo().performClick()
            waitFor("I have read this announcement.")
            compose.onNodeWithText("Cancel").performClick()
            assertTrue(server.inbox.actions.isEmpty())
            compose.onNodeWithText("Acknowledge").performClick()
            server.inbox.loseNextActionResponse = true
            compose.onNodeWithText("Acknowledge").performClick()
            waitFor("Not confirmed yet. Retry when connected.")
            assertEquals(1, server.inbox.actions.size)
            capture("inbox-unconfirmed")
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Office closure")
            compose.runOnIdle { backDispatcher.pressBack() }
            waitFor("Welcome")
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.onNodeWithText("Sign out").performScrollTo().performClick()
            waitFor("Use your account to continue.")
            signInFromLogin()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Inbox") and isSelectable()).performClick()
            waitFor("Office closure")
            compose.onNodeWithText("Office closure").performClick()
            waitFor("Not confirmed yet. Retry when connected.")
            compose.onNodeWithText("Retry").performScrollTo().performClick()
            waitFor("Acknowledged")
            compose.waitUntil(15_000) {
                server.inbox.actions.size == 2 &&
                    compose.onAllNodesWithText("Acknowledged").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isEmpty() &&
                    compose
                        .onAllNodesWithText("Not confirmed yet. Retry when connected.")
                        .fetchSemanticsNodes()
                        .isEmpty() &&
                    compose
                        .onAllNodesWithText("Saved. Updating the inbox.")
                        .fetchSemanticsNodes()
                        .isEmpty()
            }
            assertEquals(2, server.inbox.actions.size)
            assertEquals(server.inbox.actions[0], server.inbox.actions[1])
            capture("inbox-acknowledged")
        }
    }

    @Test
    fun aDefiniteConflictRequiresReviewBeforePreparingAnUpdatedAction() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Inbox") and isSelectable()).performClick()
            waitFor("Office closure")
            compose.onNodeWithText("Office closure").performClick()
            waitFor("Mark as read")
            server.inbox.rejectNextAction = true
            compose.onNodeWithText("Mark as read").performScrollTo().performClick()
            waitFor("Review updates")
            compose.onNodeWithText("Review updates").performScrollTo().performClick()
            waitFor("Acknowledge")
            compose.onNodeWithText("Mark as read").assertDoesNotExist()
            compose.onNodeWithText("Acknowledge").performScrollTo().performClick()
            waitFor("I have read this announcement.")
            compose.onNodeWithText("Acknowledge").performClick()
            waitFor("Acknowledged")
            assertEquals(2, server.inbox.actions.size)
            assertNotEquals(
                server.inbox.actions[0].operationId,
                server.inbox.actions[1].operationId,
            )
            assertEquals(2, server.inbox.actions[1].expectedVersion)
        }
    }

    @Test
    fun leavingAPendingCommandDisposesItsUiWithoutLosingTheOperation() {
        val server = NativeUiServer()
        try {
            withApplication(server) {
                signIn()
                waitFor("Choose a company")
                compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
                waitFor("Welcome")
                compose.onNode(hasText("Inbox") and isSelectable()).performClick()
                waitFor("Office closure")
                compose.onNodeWithText("Office closure").performClick()
                waitFor("Mark as read")
                server.inbox.holdNextAction = true
                compose.onNodeWithText("Mark as read").performScrollTo().performClick()
                compose.waitUntil(10_000) { server.inbox.actionStarted.isCompleted }
                compose.onNodeWithContentDescription("Back").performClick()
                waitFor("Office closure")
                compose.runOnIdle { backDispatcher.pressBack() }
                waitFor("Welcome")
                server.inbox.actionReleased.complete(Unit)
                compose.onNode(hasText("Inbox") and isSelectable()).performClick()
                waitFor("Office closure")
                compose.onNodeWithText("Office closure").performClick()
                waitFor("Not confirmed yet. Retry when connected.")
                compose.onNodeWithText("Retry").performScrollTo().performClick()
                compose.waitUntil(15_000) { server.inbox.actions.size == 2 }
                assertEquals(server.inbox.actions[0], server.inbox.actions[1])
            }
        } finally {
            server.inbox.actionReleased.complete(Unit)
        }
    }

    @Test
    fun inboxListKeepsConfirmedDenialThroughFailedCleanupAndPendingOfflineRetry() {
        val server = NativeUiServer()
        try {
            withApplication(
                server,
                module {
                    single<InboxCacheRepository> {
                        NativeInboxCacheUi(
                            EncryptedInboxCacheRepository(
                                get(),
                                get(),
                                get(named("inbox-content-key")),
                            )
                        )
                    }
                },
            ) { koin ->
                signIn()
                waitFor("Choose a company")
                compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
                waitFor("Welcome")
                compose.onNode(hasText("Inbox") and isSelectable()).performClick()
                waitFor("Office closure")
                val cache = koin.get<InboxCacheRepository>() as NativeInboxCacheUi
                cache.rejectClear = true
                server.inbox.failureCode = "mfa_required"
                refreshWhenReady()
                waitFor("Verify your account")
                compose.onNodeWithText("Office closure").assertDoesNotExist()
                assertTrue(cache.clearAttempts.get() > 0)
                compose.waitUntil(10_000) {
                    compose
                        .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                server.inbox.failureCode = null
                server.inbox.offline = true
                server.inbox.holdNextRefresh = true
                refreshWhenReady()
                compose.waitUntil(10_000) { server.inbox.refreshStarted.isCompleted }
                compose.onNodeWithText("Verify your account").assertExists()
                compose.onNodeWithText("Office closure").assertDoesNotExist()
                server.inbox.refreshReleased.complete(Unit)
                compose.waitUntil(10_000) {
                    compose
                        .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithText("Verify your account").assertExists()
                compose.onNodeWithText("Office closure").assertDoesNotExist()
                server.inbox.offline = false
                cache.rejectClear = false
                refreshWhenReady()
                waitFor("Office closure")
                compose.onNodeWithText("Verify your account").assertDoesNotExist()
                assertTrue(server.inbox.actions.isEmpty())
            }
        } finally {
            server.inbox.refreshReleased.complete(Unit)
        }
    }

    @Test
    fun inboxDetailKeepsConfirmedDenialThroughFailedCleanupAndPendingOfflineRetry() {
        val server = NativeUiServer()
        try {
            withApplication(
                server,
                module {
                    single<InboxCacheRepository> {
                        NativeInboxCacheUi(
                            EncryptedInboxCacheRepository(
                                get(),
                                get(),
                                get(named("inbox-content-key")),
                            )
                        )
                    }
                },
            ) { koin ->
                signIn()
                waitFor("Choose a company")
                compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
                waitFor("Welcome")
                compose.onNode(hasText("Inbox") and isSelectable()).performClick()
                waitFor("Office closure")
                compose.onNode(hasText("Office closure") and hasClickAction()).performClick()
                waitFor(server.inbox.firstBody)
                val cache = koin.get<InboxCacheRepository>() as NativeInboxCacheUi
                cache.rejectClear = true
                server.inbox.failureCode = "mfa_required"
                refreshWhenReady()
                waitFor("Verify your account")
                compose.onNodeWithText(server.inbox.firstBody).assertDoesNotExist()
                assertTrue(cache.clearAttempts.get() > 0)
                compose.waitUntil(10_000) {
                    compose
                        .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                server.inbox.failureCode = null
                server.inbox.offline = true
                server.inbox.holdNextRefresh = true
                refreshWhenReady()
                compose.waitUntil(10_000) { server.inbox.refreshStarted.isCompleted }
                compose.onNodeWithText("Verify your account").assertExists()
                compose.onNodeWithText(server.inbox.firstBody).assertDoesNotExist()
                server.inbox.refreshReleased.complete(Unit)
                compose.waitUntil(10_000) {
                    compose
                        .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithText("Verify your account").assertExists()
                compose.onNodeWithText(server.inbox.firstBody).assertDoesNotExist()
                server.inbox.offline = false
                cache.rejectClear = false
                refreshWhenReady()
                waitFor(server.inbox.firstBody)
                compose.onNodeWithText("Verify your account").assertDoesNotExist()
                assertTrue(server.inbox.actions.isEmpty())
            }
        } finally {
            server.inbox.refreshReleased.complete(Unit)
        }
    }

    @Test
    fun encryptedInboxSupportsPagingOfflineDetailAndCompanyIsolation() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Inbox") and isSelectable()).performClick()
            waitFor("Office closure")
            capture("inbox")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Older messages"))
            compose.onNodeWithText("Older messages").performClick()
            waitFor("Team update 31")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Latest messages"))
            compose.onNodeWithText("Latest messages").performClick()
            waitFor("Office closure")
            server.inbox.offline = true
            compose.onNodeWithText("Office closure").performClick()
            waitFor(server.inbox.firstBody)
            compose.onNodeWithText("Acknowledgement required").assertExists()
            capture("inbox-detail")
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Office closure")
            waitFor("Showing saved messages.")
            capture("inbox-offline")
            compose.runOnIdle { backDispatcher.pressBack() }
            waitFor("Welcome")
            server.inbox.offline = false
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("Change company").performClick()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Lintas Karya") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Inbox") and isSelectable()).performClick()
            waitFor("No messages yet.")
            compose.onNodeWithText("Office closure").assertDoesNotExist()
        }
    }

    @Test
    fun archivedInboxItemsDisappearWithoutRecreatingThePage() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Inbox") and isSelectable()).performClick()
            waitFor("Office closure")
            compose.onNodeWithText("Office closure").performClick()
            waitFor(server.inbox.firstBody)
            server.inbox.archived = true
            compose.waitUntil(15_000) {
                val nodes =
                    compose.onAllNodesWithContentDescription("Refresh").fetchSemanticsNodes()
                nodes.size == 1 &&
                    !nodes
                        .single()
                        .config
                        .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
            refreshWhenReady()
            waitFor("This message is no longer available.")
            compose.onNodeWithText(server.inbox.firstBody).assertDoesNotExist()
        }
    }

    @Test
    fun activeSessionsRequireConfirmationAndCurrentLogoutRemovesTheProtectedStack() {
        val server = NativeUiServer().apply { rejectSessionAccessOnce = true }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.onNodeWithText("Active sessions").performScrollTo().performClick()
            waitFor("Test iPhone")
            assertEquals(1, server.refreshes)
            compose.onNodeWithText("This device").assertExists()
            capture("sessions")
            compose.onNodeWithText("End session").performClick()
            compose.onNodeWithText("This device will need to sign in again.").assertExists()
            assertTrue(server.deviceSessions.revoked.isEmpty())
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("End session").performClick()
            compose.onNodeWithText("Confirm").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Test iPhone").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(setOf(NativeSessionsUi.OTHER), server.deviceSessions.revoked)
            compose.onNodeWithText("Sign out on this device").performScrollTo().performClick()
            waitFor("Use your account to continue.")
            compose.onNodeWithText("Active sessions").assertDoesNotExist()
            assertEquals(
                setOf(NativeSessionsUi.CURRENT, NativeSessionsUi.OTHER),
                server.deviceSessions.revoked,
            )
        }
    }

    @Test
    fun anUnconfirmedRemoteLogoutStillRemovesTheLocalIdentity() {
        val server = NativeUiServer().apply { deviceSessions.loseRevocation = true }
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNodeWithText("Sign out").assertIsDisplayed().performClick()
            waitFor("Use your account to continue.")
            compose.onNodeWithText("Choose a company").assertDoesNotExist()
            assertEquals(setOf(NativeSessionsUi.CURRENT), server.deviceSessions.revoked)
        }
    }

    @Test
    fun nativeLoginCompanySwitchLanguageAndLogout() =
        withApplication(NativeUiServer()) {
            signIn()
            waitFor("Choose a company")
            capture("companies")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNodeWithText("PT Nusa Digital").assertExists()
            compose.waitUntil(5_000) {
                compose
                    .onAllNodes(hasText("Clock in") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("All services").performScrollTo()
            val shortcuts =
                listOf("Attendance", "Work calendar").map {
                    compose
                        .onNode(hasText(it) and hasClickAction() and !isSelectable())
                        .assertIsDisplayed()
                        .fetchSemanticsNode()
                        .boundsInRoot
                }
            assertTrue(shortcuts.all { it.top == shortcuts.first().top }, "Grid bounds: $shortcuts")
            assertTrue(shortcuts.zipWithNext().all { (left, right) -> left.right < right.left })
            capture("home")
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.onNodeWithText("Alex Morgan").assertExists()
            compose.onNodeWithText("Settings").performScrollTo().performClick()
            compose.onNodeWithText("Bahasa Indonesia").performClick()
            waitFor("Pengaturan")
            compose.onNodeWithText("Bahasa Indonesia").assertIsSelected()
            compose.onNodeWithContentDescription("Kembali").assertIsDisplayed()
            compose.onNodeWithContentDescription("Kembali").performClick()
            compose.onNodeWithText("Pengaturan").performScrollTo().performClick()
            compose.onNodeWithText("English").performClick()
            waitFor("Settings")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("Change company").performClick()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Lintas Karya") and hasClickAction()).performClick()
            waitFor("Welcome")
            compose.onNodeWithText("PT Lintas Karya").assertExists()
            compose.onNodeWithText("PT Nusa Digital").assertDoesNotExist()
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.onNodeWithText("Sign out").performScrollTo().performClick()
            waitFor("Use your account to continue.")
            compose.onNodeWithText("PT Lintas Karya").assertDoesNotExist()
            compose.onNodeWithText("Use demo account").assertDoesNotExist()
        }

    @Test
    fun employeeProfileLoadsOwnAssignmentsAndClearsWhenTheCompanyChanges() =
        withApplication(NativeUiServer()) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            openProfile()
            waitFor("Personal information")
            compose.onNodeWithText("1996-02-29").assertExists()
            capture("profile")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Mobile Engineer"))
            compose.onNodeWithText("Mobile Engineer").assertIsDisplayed()
            compose.onNodeWithText("Engineering").assertExists()
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil {
                compose
                    .onAllNodes(hasText("Account") and isSelected())
                    .fetchSemanticsNodes()
                    .size == 1
            }
            compose
                .onNode(
                    (hasText("Change company") or hasContentDescription("Change company")) and
                        hasClickAction()
                )
                .performClick()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Lintas Karya") and hasClickAction()).performClick()
            waitFor("Welcome")
            openProfile()
            waitFor("No employee profile is available. Contact HR to check your account linkage.")
            compose.onNodeWithText("1996-02-29").assertDoesNotExist()
            compose.onNodeWithText("Engineering").assertDoesNotExist()
        }

    @Test
    fun profileEmploymentSelectionAndFailedRefreshReadOwnedLocalFacts() {
        val server = NativeUiServer()
        server.people.employmentCount = 2
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            openProfile()
            waitFor("Personal information")
            compose.waitUntil { server.people.recordReads.size == 2 }
            val queries = server.people.queries.size
            server.people.offline = true
            compose
                .onNode(hasText("NUSA-001") and hasClickAction())
                .performScrollTo()
                .performClick()
            waitFor("Select an employment record")
            compose.onNode(hasText("NUSA-002") and hasClickAction()).performClick()
            compose.onNode(hasText("NUSA-002") and hasClickAction()).assertIsDisplayed()
            compose.onNodeWithText("1996-02-29").assertExists()
            assertEquals(queries, server.people.queries.size)
            assertEquals(2, server.people.recordReads.size)
            refreshWhenReady()
            waitFor("Unable to connect. Check your connection.")
            compose.onNodeWithText("1996-02-29").assertExists()
            compose.onNode(hasText("NUSA-002") and hasClickAction()).assertIsDisplayed()
            capture("profile-offline")
        }
    }

    @Test
    fun leavingProfileCancelsPendingRefreshAndReturningReusesAcquiredFacts() {
        val server = NativeUiServer()
        server.people.employmentCount = 2
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            openProfile()
            waitFor("Personal information")
            server.people.holdNext = true
            refreshWhenReady()
            compose.waitUntil { server.people.started.isCompleted }
            compose.onNodeWithText("1996-02-29").assertExists()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil { server.people.cancelled.isCompleted }
            openProfile()
            waitFor("Personal information")
            assertEquals(2, server.people.recordReads.size)
        }
    }

    @Test
    fun revokedProfileAccessRemovesPreviouslyDisplayedPrivateFields() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            openProfile()
            waitFor("Personal information")
            server.people.denied = true
            refreshWhenReady()
            waitFor("Your account cannot perform this action.")
            compose.onNodeWithText("1996-02-29").assertDoesNotExist()
            compose.onNodeWithText("Private server detail").assertDoesNotExist()
        }
    }

    @Test
    fun expiredProfileAssuranceCanBeRenewedWithoutChangingCompany() {
        val server = NativeUiServer(requireMfa = true, existingAuthenticator = true)
        withApplication(server) {
            signIn()
            waitFor("Verify your account")
            compose.onNodeWithContentDescription("Verification code").performTextInput("123456")
            compose.onNode(hasText("Verify") and hasClickAction()).performScrollTo().performClick()
            waitFor("Choose a company")
            compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
            waitFor("Welcome")
            openProfile()
            waitFor("Personal information")
            kotlinx.coroutines.runBlocking { server.expireVerification() }
            refreshWhenReady()
            waitFor("Your account cannot perform this action.")
            compose.onNodeWithText("1996-02-29").assertDoesNotExist()
            compose
                .onNode(hasText("Verify your account") and hasClickAction())
                .performScrollTo()
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            waitFor("Verification code")
            compose.onNodeWithContentDescription("Verification code").performTextInput("123456")
            compose.onNode(hasText("Verify") and hasClickAction()).performScrollTo().performClick()
            waitFor("Personal information")
            compose.onNodeWithText("1996-02-29").assertExists()
            compose.onNodeWithText("Choose a company").assertDoesNotExist()
            assertEquals(0, server.refreshes)
        }
    }

    @Test
    fun enrollmentKeepsRecoveryCodesVisibleUntilExplicitAcknowledgement() {
        val server = NativeUiServer(requireMfa = true)
        withApplication(server) {
            signIn()
            waitFor("Set up authenticator")
            compose.onNode(hasText("Set up authenticator") and hasClickAction()).performClick()
            waitFor("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
            compose.onNodeWithContentDescription("Verification code").performTextInput("123456")
            compose.onNode(hasText("Verify") and hasClickAction()).performScrollTo().performClick()
            waitFor("Recovery codes")
            compose.onNodeWithText("Choose a company").assertDoesNotExist()
            compose.onNodeWithText("RECOVERY-TEST-00").assertExists()
            assertEquals(1, server.confirmations)
            compose.onNodeWithText("I have saved these codes").performScrollTo().performClick()
            waitFor("Choose a company")
            compose.onNodeWithText("RECOVERY-TEST-00").assertDoesNotExist()
        }
    }

    @Test
    fun anEnrolledAuthenticatorSkipsSetupAndInvalidProofDoesNotRefreshTokens() {
        val server = NativeUiServer(requireMfa = true, existingAuthenticator = true)
        withApplication(server) {
            signIn()
            waitFor("Verify your account")
            compose.onNodeWithText("Set up authenticator").assertDoesNotExist()
            compose.onNodeWithContentDescription("Verification code").performTextInput("654321")
            compose.onNode(hasText("Verify") and hasClickAction()).performScrollTo().performClick()
            waitFor(
                "The code is invalid or has already been used. Check your authenticator or use another recovery code."
            )
            assertEquals(0, server.refreshes)
            compose.onNodeWithText("Private diagnostic must not be displayed").assertDoesNotExist()
            compose
                .onNodeWithContentDescription("Verification code")
                .performTextReplacement("123456")
            compose.onNode(hasText("Verify") and hasClickAction()).performScrollTo().performClick()
            waitFor("Choose a company")
        }
    }

    @Test
    fun aLostConfirmationRequiresFreshSignInInsteadOfReplayingTheProof() {
        val server = NativeUiServer(requireMfa = true, loseConfirmation = true)
        withApplication(server) {
            signIn()
            waitFor("Set up authenticator")
            compose.onNode(hasText("Set up authenticator") and hasClickAction()).performClick()
            waitFor("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
            compose.onNodeWithContentDescription("Verification code").performTextInput("123456")
            compose.onNode(hasText("Verify") and hasClickAction()).performScrollTo().performClick()
            waitFor(
                "The verification result could not be confirmed. Sign out and sign in again before retrying."
            )
            compose.onNode(hasText("Verify") and hasClickAction()).assertIsNotEnabled()
            assertEquals(1, server.confirmations)
            assertEquals(0, server.refreshes)
        }
    }

    private fun withApplication(
        server: NativeUiServer,
        overrides: Module? = null,
        fileInputs: FileInputSource = UnavailableFileInputSource(),
        fileOutputs: FileOutputSource = UnavailableFileOutputSource(),
        lifecycleState: MutableState<Lifecycle.State> = mutableStateOf(Lifecycle.State.RESUMED),
        fontScale: Float = 1f,
        test: (Koin) -> Unit,
    ) {
        val directory = Files.createTempDirectory("hris-ui").toFile()
        val container =
            createAppContainer(
                createUserPreferences(directory),
                createAppDatabase(directory),
                module {
                    single<NotificationPermissionSource> {
                        object : NotificationPermissionSource {
                            override suspend fun check() = NotificationPermission.Granted

                            override suspend fun request() = NotificationPermission.Granted
                        }
                    }
                    single<NotificationDisplaySource> {
                        object : NotificationDisplaySource {
                            override suspend fun show(payload: NotificationPayload) = Unit
                        }
                    }
                    single<PushTokenSource> { UnavailablePushTokenSource() }
                },
                platform = AppPlatform.Android,
                fileInputs = fileInputs,
                fileOutputs = fileOutputs,
                http = applicationNetworkModule(AppEnvironment.Dev, server::engine),
            )
        container.koin.loadModules(
            listOf(
                module {
                    factory {
                        dev.fajar.starter.workforce.domain.usecases.ObserveWorkCalendar(
                            get(),
                            get(),
                            get(),
                            get(),
                            object : kotlin.time.Clock {
                                override fun now() =
                                    kotlin.time.Instant.parse(
                                        server.workforce.asOf.toString() + "T01:00:00Z"
                                    )
                            },
                            get(),
                        )
                    }
                    factory {
                        dev.fajar.starter.workforce.domain.attendance.usecases.ObserveAttendance(
                            get(),
                            get(),
                            get(),
                            get(),
                            get(),
                            object : kotlin.time.Clock {
                                override fun now() =
                                    kotlin.time.Instant.parse(
                                        server.workforce.asOf.toString() + "T01:00:00Z"
                                    )
                            },
                            get(),
                        )
                    }
                }
            )
        )
        if (overrides != null) container.koin.loadModules(listOf(overrides))
        val visible = mutableStateOf(true)
        try {
            compose.setContent {
                val lifecycleOwner = remember {
                    object : LifecycleOwner {
                        override val lifecycle =
                            LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                    }
                }
                DisposableEffect(lifecycleOwner) {
                    onDispose { lifecycleOwner.lifecycle.currentState = Lifecycle.State.DESTROYED }
                }
                SideEffect { lifecycleOwner.lifecycle.currentState = lifecycleState.value }
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    LocalLifecycleOwner provides lifecycleOwner,
                    LocalBackGestureDispatcher provides backDispatcher,
                ) {
                    if (visible.value) Box(Modifier.size(420.dp, 760.dp)) { StarterApp(container) }
                }
            }
            test(container.koin)
            assertTrue(
                server.unexpectedPaths.isEmpty(),
                "Unexpected endpoints: ${server.unexpectedPaths}",
            )
        } catch (failure: Throwable) {
            // A stalled native renderer must not hide the assertion that initiated capture.
            failure.printStackTrace()
            runCatching {
                capture("failure-${testName.methodName}")
                println(compose.onRoot().printToString())
            }
            throw failure
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            container.close()
            directory.deleteRecursively()
        }
    }

    private fun openProfile() {
        compose.onNode(hasText("Account") and isSelectable()).performClick()
        compose.onNodeWithText("My profile").performScrollTo().performClick()
    }

    private fun signIn() {
        waitFor("Employee services")
        compose.onNodeWithText("Skip").performClick()
        signInFromLogin()
    }

    private fun signInFromLogin() {
        waitFor("Use your account to continue.")
        compose.onNodeWithContentDescription("Email").performTextInput("alex@example.com")
        compose.onNodeWithContentDescription("Password").performTextInput("Fictional-password-123!")
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithContentDescription("Hide password").assertExists().performClick()
        compose.onNode(hasText("Sign in") and hasClickAction()).performScrollTo().performClick()
    }

    private fun refreshWhenReady(configureResponse: () -> Unit = {}) {
        compose.waitUntil(15_000) {
            compose
                .onAllNodes(hasContentDescription("Refresh") and isEnabled())
                .fetchSemanticsNodes()
                .size == 1
        }
        configureResponse()
        compose.onNodeWithContentDescription("Refresh").assertIsEnabled().performClick()
    }

    private fun openEmployeeSearch() {
        compose
            .onNodeWithContentDescription("Search employees")
            .assert(hasSetTextAction().not())
            .performScrollTo()
            .performClick()
        compose.waitUntil {
            compose
                .onAllNodes(hasContentDescription("Search employees") and hasSetTextAction())
                .fetchSemanticsNodes()
                .size == 1
        }
        compose.onNodeWithContentDescription("Search employees").assertIsFocused()
    }

    private fun openDocumentFiles() {
        compose.onNode(hasText("Documents") and hasClickAction()).performScrollTo().performClick()
        waitFor("Medical certificate")
        compose.waitUntil {
            compose
                .onAllNodes(hasContentDescription("On this device") and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("On this device").performClick()
        waitFor("On this device")
    }

    private fun waitForAction(text: String) {
        compose.waitUntil(15_000) {
            compose
                .onAllNodes(hasText(text) and hasClickAction() and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForPayslipRevision(revision: Long, current: Long) {
        waitFor("Revision $revision of $current")
        compose.waitUntil(15_000) {
            compose
                .onAllNodes(hasContentDescription("Save PDF") and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String, target: SemanticsMatcher = isRoot()) {
        val bitmap = compose.onNode(target).captureToImage().asSkiaBitmap()
        Image.makeFromBitmap(bitmap).use { image ->
            requireNotNull(image.encodeToData()).use { data ->
                val file = File("build/reports/screenshots/$name.png")
                file.parentFile.mkdirs()
                file.writeBytes(data.bytes)
            }
        }
    }
}
