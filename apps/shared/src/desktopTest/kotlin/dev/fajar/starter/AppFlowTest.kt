@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.fajar.starter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.LocalBackGestureDispatcher
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.app.di.applicationNetworkModule
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.fixtures.NativeAttendanceCacheUi
import dev.fajar.starter.fixtures.NativeDirectoryUi
import dev.fajar.starter.fixtures.NativeSessionsUi
import dev.fajar.starter.fixtures.NativeUiServer
import dev.fajar.starter.fixtures.TestBackDispatcher
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.workforce.domain.attendance.repositories.AttendanceCacheRepository
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.koin.core.module.Module
import org.koin.dsl.module

class AppFlowTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val testName = TestName()
    private val backDispatcher = TestBackDispatcher()

    @Test
    fun inboxBadgeUsesTheFullTotalUpdatesAfterReadingAndClearsOnCompanyChange() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onAllNodesWithText("Open company")[1].performClick()
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
                compose.onAllNodesWithText("Open company")[0].performClick()
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
    fun employeesTabPagesSearchesAndCancelsHiddenRequestsWithoutCrossingCompanies() {
        val server = NativeUiServer()
        try {
            withApplication(server) {
                signIn()
                waitFor("Choose a company")
                compose.onAllNodesWithText("Open company")[0].performClick()
                waitFor("Welcome")
                compose.onNode(hasText("Employees") and isSelectable()).performClick()
                waitFor("Sam Wilson")
                compose.onNodeWithContentDescription("Back").assertDoesNotExist()
                compose.onNodeWithText("HR Specialist").assertExists()
                capture("employees")
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Next page"))
                compose.onNodeWithText("Next page").performClick()
                waitFor("Colleague 21")
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("First page"))
                compose.onNodeWithText("First page").performClick()
                waitFor("Sam Wilson")
                compose
                    .onNodeWithContentDescription("Search employees")
                    .performTextReplacement("Sam")
                compose.waitUntil { server.directory.queries.lastOrNull() == "Sam" }
                waitFor("Sam Wilson")
                compose.onNodeWithText("Alex Morgan").assertDoesNotExist()
                compose.onNodeWithText("Next page").assertDoesNotExist()
                server.directory.holdNext = true
                compose
                    .onNodeWithContentDescription("Search employees")
                    .performTextReplacement("Alex")
                compose.waitUntil { server.directory.started.isCompleted }
                compose.onNode(hasText("Account") and isSelectable()).performClick()
                compose.waitUntil { server.directory.cancelled.isCompleted }
                server.directory.released.complete(Unit)
                compose.onNode(hasText("Employees") and isSelectable()).performClick()
                waitFor("Alex Morgan")
                compose.onNodeWithContentDescription("Search employees").assertTextEquals("Alex")
                compose.onNodeWithText("Sam Wilson").assertDoesNotExist()
                compose.runOnIdle { backDispatcher.pressBack() }
                waitFor("Welcome")
                compose.onNode(hasText("Home") and isSelectable()).assertIsSelected()
                compose.onNodeWithContentDescription("Change company").assertDoesNotExist()
                compose.onNode(hasText("Account") and isSelectable()).performClick()
                compose.onNodeWithContentDescription("Change company").performClick()
                waitFor("Choose a company")
                compose.onAllNodesWithText("Open company")[1].performClick()
                waitFor("Welcome")
                compose.onNode(hasText("Employees") and isSelectable()).performClick()
                waitFor("No employees found.")
                compose
                    .onNodeWithContentDescription("Search employees")
                    .assert(
                        SemanticsMatcher.expectValue(
                            androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                            androidx.compose.ui.text.AnnotatedString(""),
                        )
                    )
                compose.onNodeWithText("Sam Wilson").assertDoesNotExist()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.waitUntil {
                server.directory.departments.lastOrNull() == NativeDirectoryUi.ENGINEERING
            }
            waitFor("Alex Morgan")
            compose.onNodeWithText("Sam Wilson").assertDoesNotExist()
            capture("employees-department")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Next page"))
            compose.onNodeWithText("Next page").performClick()
            waitFor("Colleague 22")
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            compose
                .onNodeWithContentDescription("Search employees")
                .performTextReplacement("Colleague 25")
            waitFor("Colleague 25")
            compose.onNodeWithText("Next page").assertDoesNotExist()
            compose.onNodeWithContentDescription("Clear search").performClick()
            waitFor("Alex Morgan")
            compose.onNode(hasText("Account") and isSelectable()).performClick()
            compose.onNode(hasText("Employees") and isSelectable()).performClick()
            waitFor("Alex Morgan")
            compose.onNode(hasText("Engineering") and hasClickAction()).performClick()
            compose.onNodeWithText("All departments").performClick()
            waitFor("Sam Wilson")
            compose.waitUntil { server.directory.departments.lastOrNull() == null }
        }
    }

    @Test
    fun closingTheDepartmentPickerCancelsItsPendingSearch() {
        val server = NativeUiServer()
        try {
            withApplication(server) {
                signIn()
                waitFor("Choose a company")
                compose.onAllNodesWithText("Open company")[0].performClick()
                waitFor("Welcome")
                compose.onNode(hasText("Employees") and isSelectable()).performClick()
                waitFor("Sam Wilson")
                compose.onNodeWithText("All departments").performClick()
                waitFor("Search departments")
                server.directory.holdNextDepartment = true
                compose
                    .onNodeWithContentDescription("Search departments")
                    .performTextReplacement("People")
                compose.waitUntil { server.directory.departmentStarted.isCompleted }
                compose.onNodeWithContentDescription("Close").performClick()
                compose.waitUntil { server.directory.departmentCancelled.isCompleted }
                server.directory.departmentReleased.complete(Unit)
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
                compose.waitUntil {
                    server.directory.departments.lastOrNull() == NativeDirectoryUi.PEOPLE
                }
                waitFor("Sam Wilson")
                compose.onNodeWithText("Alex Morgan").assertDoesNotExist()
            }
        } finally {
            server.directory.departmentReleased.complete(Unit)
        }
    }

    @Test
    fun employeesRetainThePageOfflineButClearItAfterAccessIsDenied() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onAllNodesWithText("Open company")[0].performClick()
            waitFor("Welcome")
            compose.onNode(hasText("Employees") and isSelectable()).performClick()
            waitFor("Sam Wilson")
            server.directory.offline = true
            compose.onNodeWithContentDescription("Refresh").performClick()
            waitFor("Showing the last loaded page.")
            compose.onNodeWithText("Sam Wilson").assertExists()
            server.directory.offline = false
            server.directory.denied = true
            compose.onNodeWithContentDescription("Refresh").performClick()
            compose.waitUntil {
                compose.onAllNodesWithText("Sam Wilson").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("Showing the last loaded page.").assertDoesNotExist()
            compose.onNodeWithText("Alex Morgan").assertDoesNotExist()
            server.directory.denied = false
            compose.onNodeWithContentDescription("Refresh").performClick()
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
                compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
            waitFor("Welcome")
            compose.waitUntil(5_000) { server.workforce.attendanceActive.get() == 1 }
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
    fun attendanceRefreshClearsCacheExpiryThatArrivesDuringTheRequest() {
        val server =
            NativeUiServer().apply {
                workforce.asOf = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Jakarta"))
            }
        val cache = NativeAttendanceCacheUi()
        withApplication(server, module { single<AttendanceCacheRepository> { cache } }) {
            signIn()
            waitFor("Choose a company")
            compose.onAllNodesWithText("Open company")[0].performClick()
            compose.waitUntil(5_000) {
                compose
                    .onAllNodes(hasText("Clock in") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val release = CompletableDeferred<Unit>()
            server.workforce.attendanceGate = release
            compose.onNodeWithContentDescription("Refresh").performClick()
            compose.waitUntil(5_000) { server.workforce.attendanceActive.get() == 1 }
            cache.expire()
            waitFor("Refresh attendance before recording a new capture.")
            release.complete(Unit)
            server.workforce.attendanceGate = null
            compose.waitUntil(5_000) {
                compose
                    .onAllNodes(hasText("Clock in") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose
                .onNodeWithText("Refresh attendance before recording a new capture.")
                .assertDoesNotExist()
            server.workforce.offline = true
            compose.onNodeWithContentDescription("Refresh").performClick()
            waitFor("Unable to connect. Check your connection.")
            server.workforce.offline = false
            compose.onNodeWithContentDescription("Refresh").performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onNodeWithContentDescription("Refresh").performClick()
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
            compose.onAllNodesWithText("Open company")[1].performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
            waitFor("Work calendar")
            compose.onNodeWithText("Work calendar").performScrollTo().performClick()
            waitFor("Night shift")
            server.workforce.holdCalendar = true
            compose.onNodeWithContentDescription("Next week").performClick()
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
            compose.onNodeWithContentDescription("Refresh").performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onAllNodesWithText("Open company")[1].performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onAllNodesWithText("Open company")[1].performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
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
                compose.onAllNodesWithText("Open company")[0].performClick()
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
    fun encryptedInboxSupportsPagingOfflineDetailAndCompanyIsolation() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onAllNodesWithText("Open company")[1].performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onNodeWithContentDescription("Refresh").performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onNodeWithText("Sign out").performScrollTo().performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onAllNodesWithText("Open company")[1].performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
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
            compose.onAllNodesWithText("Open company")[1].performClick()
            waitFor("Welcome")
            openProfile()
            waitFor("No employee profile is available. Contact HR to check your account linkage.")
            compose.onNodeWithText("1996-02-29").assertDoesNotExist()
            compose.onNodeWithText("Engineering").assertDoesNotExist()
        }

    @Test
    fun revokedProfileAccessRemovesPreviouslyDisplayedPrivateFields() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onAllNodesWithText("Open company")[0].performClick()
            waitFor("Welcome")
            openProfile()
            waitFor("Personal information")
            server.people.denied = true
            compose.onNodeWithContentDescription("Refresh").performClick()
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
            compose.onAllNodesWithText("Open company")[0].performClick()
            waitFor("Welcome")
            openProfile()
            waitFor("Personal information")
            kotlinx.coroutines.runBlocking { server.expireVerification() }
            compose.onNodeWithContentDescription("Refresh").performClick()
            waitFor("Your account cannot perform this action.")
            compose.onNodeWithText("1996-02-29").assertDoesNotExist()
            compose.onNode(hasText("Verify your account") and hasClickAction()).performClick()
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
        test: () -> Unit,
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
                http = applicationNetworkModule(AppEnvironment.Dev, server::engine),
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
                CompositionLocalProvider(
                    LocalLifecycleOwner provides lifecycleOwner,
                    LocalBackGestureDispatcher provides backDispatcher,
                ) {
                    if (visible.value) Box(Modifier.size(420.dp, 760.dp)) { StarterApp(container) }
                }
            }
            test()
            assertTrue(
                server.unexpectedPaths.isEmpty(),
                "Unexpected endpoints: ${server.unexpectedPaths}",
            )
        } catch (failure: Throwable) {
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

    private fun waitFor(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asSkiaBitmap()
        Image.makeFromBitmap(bitmap).use { image ->
            requireNotNull(image.encodeToData()).use { data ->
                val file = File("build/reports/screenshots/$name.png")
                file.parentFile.mkdirs()
                file.writeBytes(data.bytes)
            }
        }
    }
}
