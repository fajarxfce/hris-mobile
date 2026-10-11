package dev.fajar.starter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.formatLocalDate
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class DatePickerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun controlledDatesKeepFullLabelsAtLargeTextAndDoNotDispatchDisabledChoices() {
        var selected by mutableStateOf(15)
        val choices = mutableListOf<Int>()
        var nextMonths = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme {
                    Box(
                        Modifier.width(320.dp)
                            .height(620.dp)
                            .background(AppColors.surface)
                            .testTag("frame")
                    ) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            AppDatePicker(
                                "Oktober 2026",
                                listOf("Sn", "Sl", "Rb", "Km", "Jm", "Sb", "Mg"),
                                List(3) { null } +
                                    (1..31).map { day ->
                                        AppDateChoice(
                                            day,
                                            day.toString(),
                                            formatLocalDate(
                                                "2026-10-${day.toString().padStart(2, '0')}",
                                                "id",
                                            ),
                                            selected == day,
                                            day >= 12,
                                        )
                                    },
                                { choices += it },
                                "Previous month",
                                "Next month",
                                null,
                                { nextMonths++ },
                            )
                        }
                    }
                }
            }
        }
        val selectedLabel = formatLocalDate("2026-10-15", "id")
        val nextLabel = formatLocalDate("2026-10-16", "id")
        compose.onNodeWithContentDescription("Previous month").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Next month").performClick()
        compose
            .onNodeWithContentDescription(formatLocalDate("2026-10-11", "id"))
            .assertIsNotEnabled()
            .performClick()
        compose.runOnIdle {
            assertEquals(1, nextMonths)
            assertTrue(choices.isEmpty())
        }
        compose.onNodeWithContentDescription(selectedLabel).assertIsSelected()
        compose
            .onNodeWithContentDescription(nextLabel)
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithContentDescription(selectedLabel).assertIsSelected()
        compose.onNodeWithContentDescription(nextLabel).assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(listOf(16), choices)
            selected = 16
        }
        compose.onNodeWithContentDescription(nextLabel).assertIsSelected()
        val date = compose.onNodeWithContentDescription(nextLabel).fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertTrue(date.left >= frame.left && date.right <= frame.right)
        Image.makeFromBitmap(compose.onRoot().captureToImage().asSkiaBitmap()).use { capture ->
            checkNotNull(capture.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/date-picker-large-text.png").writeBytes(data.bytes)
            }
        }
    }
}
