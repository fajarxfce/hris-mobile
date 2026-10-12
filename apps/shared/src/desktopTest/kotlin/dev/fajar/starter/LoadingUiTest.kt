package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class LoadingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun inlineLoadingKeepsItsLabelAndAdjacentActionVisibleAtLargeText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme {
                    Column(Modifier.size(320.dp, 160.dp).testTag("frame")) {
                        AppLoading("Memuat data…", compact = true)
                        AppButton("Kembali", {})
                    }
                }
            }
        }
        compose.onNodeWithText("Memuat data…").assertIsDisplayed()
        val action = compose.onNode(hasText("Kembali") and hasClickAction())
        action.assertIsDisplayed().assertIsEnabled()
        val bounds = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertTrue(action.fetchSemanticsNode().boundsInRoot.bottom <= bounds.bottom)
    }
}
