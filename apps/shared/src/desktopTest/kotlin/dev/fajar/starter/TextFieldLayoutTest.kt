package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.AppTextField
import dev.fajar.starter.designsystem.theme.AppTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class TextFieldLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun textFieldsKeepTheSingleLineDefaultAndAcceptMultilineInputAtLargeText() {
        var value by mutableStateOf("First\nSecond")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme {
                    Column(Modifier.width(360.dp).padding(16.dp)) {
                        AppTextField("Single line", "Word ".repeat(12), {})
                        AppTextField("Notes", value, { value = it }, maxLines = 4)
                    }
                }
            }
        }
        val single = mutableListOf<TextLayoutResult>()
        compose.onNodeWithContentDescription("Single line").performSemanticsAction(
            SemanticsActions.GetTextLayoutResult
        ) {
            it(single)
        }
        assertEquals(1, single.single().lineCount)
        val multiline = mutableListOf<TextLayoutResult>()
        compose.onNodeWithContentDescription("Notes").assertIsDisplayed().performSemanticsAction(
            SemanticsActions.GetTextLayoutResult
        ) {
            it(multiline)
        }
        assertEquals(2, multiline.single().lineCount)
        compose.onNodeWithContentDescription("Notes").performTextReplacement("New\nText")
        compose.runOnIdle { assertEquals("New\nText", value) }
    }
}
