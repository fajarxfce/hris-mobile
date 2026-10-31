@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.designsystem.theme.AppTheme
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class CompactFiltersLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun periodAndStatusShareOneRowOnPhoneWithStepControlsAtTheEnd() {
        showFilters(390, 1f, "Oktober 2026", "Semua status")
        compose.onNodeWithTag("filters").assertHeightIsEqualTo(48.dp)
        val month = compose.onNodeWithText("Oktober 2026").fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithText("Semua status").fetchSemanticsNode().boundsInRoot
        val previous =
            compose
                .onNodeWithContentDescription("Bulan sebelumnya")
                .fetchSemanticsNode()
                .boundsInRoot
        val next =
            compose
                .onNodeWithContentDescription("Bulan berikutnya")
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(
            month.right <= status.left &&
                status.right <= previous.left &&
                previous.right <= next.left
        )
        assertEquals(month.top, status.top)
        checkLabels("Oktober 2026", "Semua status")
        screenshot("compact-filters-phone")
    }

    @Test
    fun longSelectedStatusReflowsAtTwoHundredPercentWithoutClippingOrSmallTargets() {
        showFilters(320, 2f, "September 2026", "Menunggu persetujuan")
        checkLabels("September 2026", "Menunggu persetujuan")
        val status =
            compose.onNodeWithText("Menunggu persetujuan").fetchSemanticsNode().boundsInRoot
        val month = compose.onNodeWithText("September 2026").fetchSemanticsNode().boundsInRoot
        assertTrue(status.top >= month.bottom)
        screenshot("compact-filters-large-text")
    }

    @Test
    fun keyboardAndClickDispatchIndependentlyAndDisabledStepDoesNotDispatch() {
        var previousEnabled by mutableStateOf(true)
        var previous = 0
        var next = 0
        var picker = 0
        var status = 0
        compose.setContent {
            AppTheme {
                AppPeriodNavigation(
                    "Previous",
                    "Next",
                    { previous++ },
                    { next++ },
                    Modifier.width(390.dp),
                    previousEnabled = previousEnabled,
                ) {
                    FlowRow {
                        AppFilterChip("October 2026", false, { picker++ }, icon = null)
                        AppFilterChip("Approved", true, { status++ }, icon = null)
                    }
                }
            }
        }
        compose.onNodeWithText("Approved").assertIsSelected().performClick()
        compose.onNodeWithText("October 2026").performClick()
        compose.onNodeWithContentDescription("Previous").performClick()
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithContentDescription("Next").performSemanticsAction(
            SemanticsActions.RequestFocus
        ) {
            it()
        }
        compose.onNodeWithContentDescription("Next").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { previousEnabled = false }
        compose.onNodeWithContentDescription("Previous").assertIsNotEnabled().performClick()
        assertEquals(listOf(1, 2, 1, 1), listOf(previous, next, picker, status))
    }

    private fun showFilters(width: Int, scale: Float, month: String, status: String) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                AppTheme(reduceMotion = true) {
                    Box(
                        Modifier.width(width.dp)
                            .background(AppColors.canvas)
                            .padding(horizontal = 16.dp)
                    ) {
                        AppPeriodNavigation(
                            "Bulan sebelumnya",
                            "Bulan berikutnya",
                            {},
                            {},
                            Modifier.fillMaxWidth().testTag("filters"),
                        ) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AppFilterChip(month, false, {}, icon = null)
                                AppFilterChip(status, status != "Semua status", {}, icon = null)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkLabels(vararg labels: String) {
        val frame = compose.onNodeWithTag("filters").fetchSemanticsNode().boundsInRoot
        labels.forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(
                SemanticsActions.GetTextLayoutResult
            ) {
                it(layouts)
            }
            assertFalse(layouts.single().hasVisualOverflow, label)
            val node =
                compose.onNodeWithText(label).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= frame.left && bounds.right <= frame.right)
        }
        listOf("Bulan sebelumnya", "Bulan berikutnya").forEach {
            compose
                .onNodeWithContentDescription(it)
                .assertWidthIsEqualTo(48.dp)
                .assertHeightIsEqualTo(48.dp)
        }
    }

    private fun screenshot(name: String) {
        Image.makeFromBitmap(compose.onRoot().captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                val file = File("build/reports/screenshots/$name.png")
                file.parentFile.mkdirs()
                file.writeBytes(data.bytes)
            }
        }
    }
}
