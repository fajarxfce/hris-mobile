package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.formatMonth
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class MonthPickerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun monthsRemainReadableAtLargeTextAndUnavailableChoicesCannotDispatch() {
        val selected = mutableStateOf(10)
        var selections = 0
        var previousYears = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme {
                    Box(Modifier.width(320.dp).height(640.dp).testTag("frame")) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            AppMonthPicker(
                                "2026",
                                (1..12).map { month ->
                                    AppMonthChoice(
                                        month,
                                        formatMonth(
                                            "2026-${month.toString().padStart(2, '0')}-01",
                                            "id",
                                            includeYear = false,
                                        ),
                                        selected.value == month,
                                        month <= 10,
                                    )
                                },
                                {
                                    selected.value = it
                                    selections++
                                },
                                "Previous year",
                                "Next year",
                                { previousYears++ },
                                null,
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Next year").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Previous year").performClick()
        compose.runOnIdle { assertEquals(1, previousYears) }
        compose.onNodeWithText("Desember").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, selections) }
        compose.onNodeWithText("Oktober").performScrollTo().assertIsSelected()
        compose
            .onNodeWithText("September")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithText("September").assertIsSelected()
        val month = compose.onNodeWithText("September").fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertTrue(month.left >= frame.left && month.right <= frame.right)
        compose.runOnIdle {
            assertEquals(9, selected.value)
            assertEquals(1, selections)
        }
    }
}
