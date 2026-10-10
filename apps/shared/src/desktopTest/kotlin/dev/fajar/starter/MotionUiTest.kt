package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class MotionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tabInputSurvivesNavigationButIsRemovedWithTheAccountScope() {
        val selected = mutableStateOf("one")
        val account = mutableStateOf("first")
        compose.setContent {
            AppTheme(reduceMotion = true) {
                key(account.value) {
                    AppTabContent(
                        selected.value,
                        stateKey = { it },
                        Modifier.size(360.dp, 600.dp),
                    ) { tab ->
                        var input by rememberSaveable { mutableStateOf("") }
                        AppPage {
                            AppHeading(tab)
                            AppTextField("Value", input, { input = it })
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Value").performTextInput("Retained draft")
        compose.runOnIdle { selected.value = "two" }
        compose.onNodeWithContentDescription("Value").assertTextEquals("")
        compose.runOnIdle { selected.value = "one" }
        compose.onNodeWithContentDescription("Value").assertTextContains("Retained draft")
        compose.runOnIdle { account.value = "second" }
        compose.onNodeWithContentDescription("Value").assertTextEquals("")
    }

    @Test
    fun rapidTabChangesFinishAtTheLatestSelectionAndDisposeOutgoingContent() {
        val selected = mutableStateOf("one")
        val reduced = mutableStateOf(false)
        val active = mutableSetOf<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme(reduceMotion = reduced.value) {
                AppTabContent(selected.value, stateKey = { it }, Modifier.size(360.dp, 600.dp)) {
                    tab ->
                    DisposableEffect(tab) {
                        active.add(tab)
                        onDispose { active.remove(tab) }
                    }
                    AppText(tab, Modifier.testTag(tab))
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { selected.value = "two" }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { selected.value = "three" }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { selected.value = "one" }
        compose.mainClock.advanceTimeBy(220)
        compose.onNodeWithTag("one").assertExists()
        compose.runOnIdle { assertEquals(setOf("one"), active) }
        compose.runOnIdle {
            reduced.value = true
            selected.value = "two"
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("two").assertExists()
        compose.onNodeWithTag("one").assertDoesNotExist()
        compose.runOnIdle { assertEquals(setOf("two"), active) }
    }
}
