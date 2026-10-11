@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import io.github.composefluent.icons.regular.Home
import io.github.composefluent.icons.regular.Person
import java.io.File
import kotlin.test.assertEquals
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class AdaptiveUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun compactHeaderAndActionTileKeepLargeTextAndKeyboardActionsInsideTheScreen() {
        var clicks = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme {
                    Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                        AppPage(
                            contentPadding = PaddingValues(16.dp),
                            spacing = 16.dp,
                            topBar = {
                                AppTopBar(
                                    title = "Employee updates",
                                    navigation = {
                                        AppIconButton(Icons.Regular.ArrowLeft, "Back", {})
                                    },
                                    actions = {
                                        AppIconButton(Icons.Regular.ArrowSync, "Refresh", {})
                                    },
                                )
                            },
                        ) {
                            AppActionTile(
                                title = "Work calendar",
                                supportingText = "Shifts and days off",
                                icon = Icons.Regular.CalendarLtr,
                                modifier = Modifier.testTag("work-tile"),
                                onClick = { clicks++ },
                            )
                        }
                    }
                }
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
        compose.onNodeWithContentDescription("Back").assertWidthIsEqualTo(48.dp)
        compose.onNodeWithContentDescription("Refresh").assertHeightIsEqualTo(48.dp)
        compose
            .onNodeWithText("Employee updates")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val tile = compose.onNodeWithTag("work-tile").assertHeightIsAtLeast(88.dp)
        tile.performClick()
        tile.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) {
            it()
        }
        tile.performKeyInput { pressKey(Key.Enter) }
        val bounds = tile.fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        kotlin.test.assertTrue(bounds.left >= frame.left && bounds.right <= frame.right)
        compose.runOnIdle { assertEquals(2, clicks) }
        capture("compact-header-action-large-text")
    }

    @Test
    fun narrowListRowsKeepMetadataVisibleAtLargeTextScaleWithOneAction() {
        var clicks = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme {
                    Box(Modifier.width(360.dp).testTag("frame").padding(24.dp)) {
                        AppListRow(
                            title = "Office closure and service availability",
                            supportingText = "The office will be closed on Friday.",
                            onClick = { clicks++ },
                            modifier = Modifier.testTag("message"),
                            metadata = { AppText("Oct 10, 2026") },
                        )
                    }
                }
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithTag("message").assertHeightIsAtLeast(64.dp).performClick()
        compose.onNodeWithText("Oct 10, 2026", useUnmergedTree = true).assertIsDisplayed()
        val row = compose.onNodeWithTag("message").fetchSemanticsNode().boundsInRoot
        val metadata =
            compose
                .onNodeWithText("Oct 10, 2026", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        kotlin.test.assertTrue(row.contains(metadata.topLeft) && row.contains(metadata.bottomRight))
        compose.runOnIdle { assertEquals(1, clicks) }
        capture("list-row-large-text")
    }

    @Test
    fun resizingMovesNavigationWithoutRecreatingTheContent() {
        val width = mutableStateOf(360.dp)
        val selected = mutableStateOf(0)
        var mounted = 0
        var disposed = 0
        compose.setContent {
            AppTheme {
                Box(Modifier.size(width.value, 720.dp).testTag("frame")) {
                    AppNavigationScaffold(
                        listOf(
                            AppNavigationItem(0, "Overview", Icons.Regular.Home),
                            AppNavigationItem(1, "Account", Icons.Regular.Person),
                        ),
                        selected.value,
                        { selected.value = it },
                    ) {
                        DisposableEffect(Unit) {
                            mounted++
                            onDispose { disposed++ }
                        }
                        AppPage {
                            AppHeading("Panel")
                            AppText("Current selection: ${selected.value}")
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("app-bottom-navigation").assertExists()
        compose
            .onNode(hasText("Account") and hasClickAction())
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNode(hasText("Account") and hasClickAction()).assertIsSelected()
        compose.runOnIdle { width.value = 720.dp }
        compose.onNodeWithTag("app-bottom-navigation").assertDoesNotExist()
        compose.onNodeWithTag("app-side-navigation").assertWidthIsEqualTo(96.dp)
        compose.onNode(hasText("Account") and hasClickAction()).assertIsSelected()
        compose.runOnIdle { width.value = 1000.dp }
        compose.onNodeWithTag("app-side-navigation").assertWidthIsEqualTo(224.dp)
        compose.onNodeWithText("Current selection: 1").assertExists()
        capture("adaptive-expanded")
        compose.runOnIdle {
            assertEquals(1, mounted)
            assertEquals(0, disposed)
            width.value = 360.dp
        }
        compose.onNodeWithTag("app-bottom-navigation").assertExists()
        compose.runOnIdle {
            assertEquals(1, mounted)
            assertEquals(0, disposed)
        }
    }

    @Test
    fun largeTextKeepsFieldLabelsErrorsAndKeyboardActivationAccessible() {
        val requester = FocusRequester()
        val text = mutableStateOf("")
        var clicks = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme {
                    Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                        AppPage {
                            AppHeading("Sign in")
                            AppTextField(
                                "Email",
                                text.value,
                                { text.value = it },
                                error = "Check the email address.",
                            )
                            AppButton("Continue", { clicks++ }, Modifier.focusRequester(requester))
                        }
                    }
                }
            }
        }
        compose
            .onNodeWithText("Sign in")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose
            .onNodeWithContentDescription("Email")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Error, "Check the email address.")
            )
        compose
            .onNode(hasContentDescription("Email") and hasSetTextAction())
            .performTextInput("demo@example.com")
        compose.onNodeWithText("Continue").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { requester.requestFocus() }
        compose.onNodeWithText("Continue").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, clicks) }
        capture("accessibility-large-text")
    }

    @Test
    fun fourLargeTextNavigationLabelsKeepOneLineAndTheirFullAccessibleNames() {
        val selected = mutableStateOf(0)
        val labels = listOf("Beranda", "Inbox", "Karyawan", "Akun")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme {
                    Box(Modifier.width(360.dp).testTag("frame")) {
                        AppBottomNavigation(
                            labels.mapIndexed { index, label ->
                                AppNavigationItem(index, label, Icons.Regular.Person)
                            },
                            selected.value,
                            { selected.value = it },
                        )
                    }
                }
            }
        }
        labels.forEach { label ->
            compose.onNode(hasText(label) and isSelectable()).assertHeightIsAtLeast(48.dp)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
            ) {
                it(layouts)
            }
            assertEquals(1, layouts.single().lineCount)
        }
        compose.onNode(hasText("Karyawan") and isSelectable()).performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(2, selected.value) }
        capture("navigation-large-text")
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("frame").captureToImage().asSkiaBitmap()
        val data = requireNotNull(Image.makeFromBitmap(bitmap).encodeToData())
        File("build/reports/screenshots/$name.png").apply {
            parentFile.mkdirs()
            writeBytes(data.bytes)
        }
    }
}
