@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.CalendarLtr
import io.github.composefluent.icons.regular.ChevronLeft
import io.github.composefluent.icons.regular.ChevronRight
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.Test

class FilterChipLabelUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun completeMonthAndYearRemainVisibleBetweenActionsAtLargeText() {
        var clicks = 0
        val label = "September 2026"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme {
                    Row(Modifier.width(320.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIconButton(Icons.Regular.ChevronLeft, "Previous month", {})
                        AppFilterChip(
                            label,
                            false,
                            { clicks++ },
                            Modifier.weight(1f),
                            icon = Icons.Regular.CalendarLtr,
                            maxLines = 2,
                        )
                        AppIconButton(Icons.Regular.ChevronRight, "Next month", {})
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(
            SemanticsActions.GetTextLayoutResult
        ) {
            it(layouts)
        }
        assertFalse(layouts.single().hasVisualOverflow)
        compose.onNodeWithContentDescription("Previous month").assertWidthIsEqualTo(48.dp)
        compose.onNodeWithContentDescription("Next month").assertWidthIsEqualTo(48.dp)
        compose.onNodeWithText(label).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
