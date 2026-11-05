package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import kotlin.test.*
import org.junit.Rule
import org.junit.Test

class SwipeTabsUiTest {
    @get:Rule val compose = createComposeRule()
    private val selected = mutableStateOf(0)
    private val enabled = mutableStateOf(true)
    private val changes = mutableListOf<Int>()
    private val active = mutableSetOf<Int>()

    private fun render(reducedMotion: Boolean = false) {
        compose.setContent {
            AppTheme(reduceMotion = reducedMotion) {
                Column(Modifier.size(375.dp, 650.dp)) {
                    AppTabs(
                        listOf(AppTabItem(0, "First"), AppTabItem(1, "Second")),
                        selected.value,
                        { selected.value = it },
                        enabled = enabled.value,
                    )
                    AppSwipeTabContent(
                        listOf(0, 1),
                        selected.value,
                        {
                            changes += it
                            selected.value = it
                        },
                        { "page-$it" },
                        Modifier.weight(1f).fillMaxWidth().testTag("pages"),
                        enabled = enabled.value,
                    ) { page, isActive ->
                        if (isActive)
                            DisposableEffect(page) {
                                active += page
                                onDispose { active -= page }
                            }
                        var draft by rememberSaveable { mutableStateOf("") }
                        Column {
                            AppText("Page $page")
                            AppSearchField(draft, { draft = it }, "Draft $page", "Clear $page")
                        }
                    }
                }
            }
        }
    }

    private fun swipe(left: Boolean) {
        compose.onNodeWithTag("pages").performTouchInput {
            val from = if (left) .85f else .15f
            val to = if (left) .15f else .85f
            swipe(Offset(width * from, height * .6f), Offset(width * to, height * .6f), 350)
        }
    }

    @Test
    fun buttonsAndSwipesRetainDraftsAndExposeOnlyTheActivePage() {
        render()
        compose.runOnIdle { assertEquals(setOf(0), active) }
        compose.onNodeWithContentDescription("Draft 0").performTextInput("Retained draft")
        compose.onNodeWithContentDescription("Draft 0").performImeAction()
        compose.onNode(hasText("Second") and isSelectable()).performClick()
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        compose.onNodeWithText("Page 0").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(setOf(1), active)
            assertTrue(changes.isEmpty())
        }
        swipe(false)
        compose.onNode(hasText("First") and isSelectable()).assertIsSelected()
        compose.onNodeWithContentDescription("Draft 0").assertTextContains("Retained draft")
        compose.runOnIdle {
            assertEquals(setOf(0), active)
            assertEquals(listOf(0), changes)
        }
    }

    @Test
    fun externalSelectionDuringAnUnfinishedDragWinsAndDoesNotBounceBack() {
        render()
        compose.onNodeWithTag("pages").performTouchInput {
            down(Offset(width * .85f, height * .6f))
            moveBy(Offset(-width * .3f, 0f), 150)
        }
        compose.runOnIdle { selected.value = 1 }
        compose.onNodeWithTag("pages").performTouchInput { up() }
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        compose.onNode(hasText("Second") and isSelectable()).assertIsSelected()
        compose.runOnIdle {
            assertEquals(setOf(1), active)
            assertTrue(changes.isEmpty())
        }
        swipe(false)
        compose.onNodeWithText("Page 0").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(0), changes) }
    }

    @Test
    fun aNewGestureCanInterruptTabAnimationAndSettleBackOnTheFirstPage() {
        render()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected.value = 1 }
        compose.mainClock.advanceTimeBy(32)
        swipe(false)
        compose.mainClock.advanceTimeBy(1500)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Page 0").assertIsDisplayed()
        compose.onNode(hasText("First") and isSelectable()).assertIsSelected()
        compose.runOnIdle {
            assertEquals(setOf(0), active)
            assertEquals(listOf(0), changes)
        }
    }

    @Test
    fun reducedMotionAndDisabledGesturesStillHonorExternalSelection() {
        enabled.value = false
        render(reducedMotion = true)
        swipe(true)
        compose.onNodeWithText("Page 0").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(changes.isEmpty())
            selected.value = 1
        }
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        compose.runOnIdle { enabled.value = true }
        swipe(false)
        compose.onNodeWithText("Page 0").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(0), changes) }
    }

    @Test
    fun filterIconAnnouncesItsSelectionWithoutAddingAVisibleLabelRow() {
        var filtered by mutableStateOf(false)
        compose.setContent {
            AppTheme {
                AppFilterButton(
                    "Filter departments",
                    filtered,
                    if (filtered) "Engineering" else "All departments",
                    { filtered = !filtered },
                )
            }
        }
        val control = compose.onNodeWithContentDescription("Filter departments")
        control.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertIsNotSelected()
        control.performClick().assertIsSelected()
        control.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Engineering")
        )
        compose.onNodeWithText("Engineering").assertDoesNotExist()
        control.performClick().assertIsNotSelected()
    }

    @Test
    fun fadingActionCannotBeClickedAndItsTransitionCanBeReversed() {
        var visible by mutableStateOf(true)
        var clicked = 0
        compose.setContent {
            AppTheme(reduceMotion = false) {
                AppFloatingActionButton(
                    Icons.Regular.Add,
                    "New conversation",
                    { clicked++ },
                    visible = visible,
                )
            }
        }
        val button = compose.onNodeWithContentDescription("New conversation")
        button.assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeBy(32)
        button.assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertEquals(0, clicked)
            visible = true
        }
        compose.mainClock.advanceTimeBy(300)
        compose.mainClock.autoAdvance = true
        button.assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, clicked) }
    }

    @Test
    fun scrollActionIgnoresHorizontalGesturesAndResetsWithoutResettingTheList() {
        var page by mutableStateOf(0)
        compose.setContent {
            AppTheme {
                Column(Modifier.size(375.dp, 650.dp)) {
                    AppText("Fixed header")
                    AppFloatingActionLayout(
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("scroll-area"),
                        stateKey = page,
                        action = { visible ->
                            AppFloatingActionButton(
                                Icons.Regular.Add,
                                "Create item",
                                {},
                                Modifier.align(Alignment.BottomEnd),
                                visible = visible,
                            )
                        },
                    ) {
                        LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState()) {
                            items(50, key = { it }) { AppText("Item $it", Modifier.height(60.dp)) }
                        }
                    }
                }
            }
        }
        val header = compose.onNodeWithText("Fixed header").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("scroll-area").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("Create item").assertIsDisplayed()
        compose.onNodeWithTag("scroll-area").performTouchInput { swipeUp() }
        compose.onNodeWithContentDescription("Create item").assertDoesNotExist()
        compose.onNodeWithText("Item 0").assertDoesNotExist()
        compose.runOnIdle { page = 1 }
        compose.onNodeWithContentDescription("Create item").assertIsDisplayed()
        compose.onNodeWithText("Item 0").assertDoesNotExist()
        assertEquals(
            header,
            compose.onNodeWithText("Fixed header").fetchSemanticsNode().boundsInRoot,
        )
        compose.onNodeWithTag("scroll-area").performTouchInput { swipeDown() }
        compose.onNodeWithContentDescription("Create item").assertIsDisplayed()
    }
}
