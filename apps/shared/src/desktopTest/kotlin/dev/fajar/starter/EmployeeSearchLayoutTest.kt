package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.people.presentation.directory.*
import dev.fajar.starter.people.presentation.directory.pages.EmployeeSearchPage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class EmployeeSearchLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun searchAndBackStayVisibleWhileLargeIndonesianResultsScroll() {
        val events = mutableListOf<EmployeesEvent>()
        var state by
            mutableStateOf(
                EmployeesState(
                    page =
                        EmployeeDirectoryContent(
                            (1..40).map {
                                EmployeeRowState(
                                    "$it",
                                    "Karyawan $it",
                                    "Software Engineer",
                                    "Engineering",
                                    "K",
                                )
                            },
                            nextCursor = null,
                        )
                )
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                        EmployeeSearchPage(state, rememberLazyListState()) { event ->
                            events += event
                            if (event is EmployeesEvent.QueryChanged)
                                state = state.copy(query = event.value)
                        }
                    }
                }
            }
        }
        val search = compose.onNodeWithContentDescription("Cari karyawan")
        val back = compose.onNodeWithContentDescription("Kembali")
        search.assertIsFocused().assertHeightIsAtLeast(48.dp).performTextInput("Karyawan")
        search.performImeAction()
        val searchBounds = search.fetchSemanticsNode().boundsInRoot
        val backBounds = back.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertTrue(searchBounds.left >= frame.left && searchBounds.right <= frame.right)
        assertTrue(backBounds.right <= searchBounds.left)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Karyawan 40"))
        compose.onNodeWithText("Karyawan 40").assertIsDisplayed()
        assertEquals(searchBounds, search.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        assertEquals(backBounds, back.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        search.assertIsNotFocused()
        back.performClick()
        compose.runOnIdle {
            assertEquals("Karyawan", state.query)
            assertEquals(EmployeesEvent.BackRequested, events.last())
        }
        Image.makeFromBitmap(compose.onRoot().captureToImage().asSkiaBitmap()).use { image ->
            checkNotNull(image.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/employee-search-indonesian-large-text.png")
                    .writeBytes(data.bytes)
            }
        }
    }

    @Test
    fun failedSearchCanBeRetriedWithoutEditingItsQuery() {
        val events = mutableListOf<EmployeesEvent>()
        var state by
            mutableStateOf(
                EmployeesState(
                    query = "Alex",
                    failure = Failure(FailureKind.Network, "Unavailable"),
                )
            )
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp)) {
                    EmployeeSearchPage(state, rememberLazyListState(), events::add)
                }
            }
        }
        val retry = compose.onNode(hasText("Retry") and hasClickAction())
        retry.performClick()
        compose.runOnIdle {
            assertEquals(listOf<EmployeesEvent>(EmployeesEvent.RefreshRequested), events)
            state = state.copy(loading = true)
        }
        retry.assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, events.size) }
        compose.onNodeWithContentDescription("Search employees").assertTextContains("Alex")
    }
}
