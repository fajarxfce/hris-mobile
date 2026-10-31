package dev.fajar.starter

import androidx.compose.foundation.layout.*
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
import kotlin.test.*
import org.junit.Rule
import org.junit.Test

class FormChoiceLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longChoiceWrapsAtLargeTextAndExposesOneDisabledOrEnabledAction() {
        var enabled by mutableStateOf(true)
        var clicks = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme {
                    Box(Modifier.width(320.dp).testTag("frame")) {
                        AppSelectField(
                            "Kategori pengeluaran",
                            "Perjalanan dinas dan transportasi antarkota",
                            { clicks++ },
                            enabled = enabled,
                        )
                    }
                }
            }
        }
        val choice = compose.onNode(hasText("Kategori pengeluaran") and hasClickAction())
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        choice.assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, clicks)
        val bounds = choice.fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= frame.left && bounds.right <= frame.right)
        compose.onNodeWithText("Perjalanan dinas dan transportasi antarkota").assertIsDisplayed()
        compose.runOnIdle { enabled = false }
        choice.assertIsNotEnabled().performClick()
        assertEquals(1, clicks)
    }
}
