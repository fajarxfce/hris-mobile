@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.CalendarLtr
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class PageLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun lazyPageKeepsBackAndRefreshAtTheTopWhileTheLastItemIsVisible() = checkFixedBar(lazy = true)

    @Test
    fun scrollingPageKeepsBackAndRefreshOutsideItsContentPadding() = checkFixedBar(lazy = false)

    private fun checkFixedBar(lazy: Boolean) {
        var back = 0
        var refresh = 0
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 480.dp).testTag("frame")) {
                    val topBar: @Composable () -> Unit = {
                        AppTopBar(
                            "Attendance",
                            navigation = {
                                AppIconButton(Icons.Regular.ArrowLeft, "Back", { back++ })
                            },
                            actions = {
                                AppIconButton(Icons.Regular.ArrowSync, "Refresh", { refresh++ })
                            },
                        )
                    }
                    if (lazy)
                        AppLazyPage(contentPadding = PaddingValues(16.dp), topBar = topBar) {
                            items(40, key = { it }) {
                                AppText("Entry $it", Modifier.heightIn(min = 48.dp))
                            }
                        }
                    else
                        AppPage(contentPadding = PaddingValues(16.dp), topBar = topBar) {
                            repeat(40) { AppText("Entry $it", Modifier.heightIn(min = 48.dp)) }
                        }
                }
            }
        }
        val header = compose.onNodeWithTag("app-top-bar").assertHeightIsEqualTo(56.dp)
        val initial = header.fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertEquals(frame.top, initial.top)
        if (lazy) compose.onNode(hasScrollToIndexAction()).performScrollToIndex(39)
        else compose.onNodeWithText("Entry 39").performScrollTo()
        compose.onNodeWithText("Entry 39").assertIsDisplayed()
        assertEquals(initial, header.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Refresh").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, back)
            assertEquals(1, refresh)
        }
    }

    @Test
    fun menuUsesFourColumnsAndReflowsAtLargeTextWithoutLosingActions() {
        val scale = mutableStateOf(1f)
        val titles = listOf("Attendance", "Work calendar", "Inbox", "My profile")
        val selected = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                AppTheme {
                    Box(Modifier.size(360.dp, 680.dp).testTag("frame")) {
                        AppPage(contentPadding = PaddingValues(16.dp)) {
                            AppMenuGrid(titles, itemKey = { it }) { title ->
                                AppMenuItem(
                                    title,
                                    Icons.Regular.CalendarLtr,
                                    { selected.add(title) },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(4)
        val firstRow = titles.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        assertTrue(firstRow.all { it.top == firstRow.first().top })
        assertTrue(firstRow.zipWithNext().all { (left, right) -> left.right < right.left })
        compose.runOnIdle { scale.value = 2f }
        val large =
            titles.map {
                compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            }
        assertEquals(large[0].top, large[1].top)
        assertTrue(large[2].top > large[0].bottom)
        assertEquals(large[2].top, large[3].top)
        compose.onNodeWithText("Work calendar").performClick()
        compose.onNodeWithText("Inbox").performSemanticsAction(SemanticsActions.RequestFocus) {
            it()
        }
        compose.onNodeWithText("Inbox").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(listOf("Work calendar", "Inbox"), selected) }
    }

    @Test
    fun preferencesExposeOneRadioOrSwitchActionAndRespectSavingState() {
        val language = mutableStateOf("English")
        val locked = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        var changes = 0
        compose.setContent {
            AppTheme {
                Column(Modifier.width(360.dp)) {
                    Column(Modifier.selectableGroup()) {
                        listOf("English", "Bahasa Indonesia").forEach { title ->
                            AppRadioRow(
                                title,
                                language.value == title,
                                {
                                    language.value = title
                                    changes++
                                },
                                enabled = enabled.value,
                            )
                        }
                    }
                    AppSwitchRow(
                        "App lock",
                        locked.value,
                        {
                            locked.value = it
                            changes++
                        },
                        enabled = enabled.value,
                    )
                }
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
        compose.onNodeWithText("English").assertIsSelected()
        compose
            .onNodeWithText("Bahasa Indonesia")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick()
            .assertIsSelected()
        compose.onNodeWithText("English").assertIsNotSelected()
        compose
            .onNodeWithText("App lock")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
            .performClick()
            .assertIsOn()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("English").assertIsNotEnabled().performClick()
        compose.onNodeWithText("App lock").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(2, changes) }
    }
}
