@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.presentation.navigation.SpaceChatThreadViewport
import dev.fajar.starter.collaboration.presentation.spacethread.*
import dev.fajar.starter.collaboration.presentation.spacethread.pages.SpaceChatThreadPage
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect

class SpaceChatComposerFocusTest {
    @Test
    fun consecutiveSendsKeepInputFocusWhileTheRealGroupUseCaseWaitsForLocalStorage() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val f = SpaceChatThreadFixture()
            val release = CompletableDeferred<Unit>()
            f.beforeHandoff = { release.await() }
            val model = f.model()
            val store = ViewModelStore().also { it.put("group", model) }
            try {
                setContent {
                    val state by model.state.collectAsState()
                    LaunchedEffect(model) { model.onEvent(SpaceChatThreadEvent.Entered) }
                    LaunchedEffect(model) { model.effects.collect() }
                    CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                        AppTheme(reduceMotion = true) {
                            val list = rememberLazyListState()
                            SpaceChatThreadViewport(state, list)
                            SpaceChatThreadPage(state, list, model::onEvent)
                        }
                    }
                }
                waitUntil(timeoutMillis = 10_000) { model.state.value.canCompose }
                val input = onNodeWithContentDescription("Message")
                input.performClick().performTextInput("First original")
                waitUntil(timeoutMillis = 5_000) { model.state.value.draft == "First original" }
                onNodeWithContentDescription("Send message").performClick()
                waitUntil(timeoutMillis = 5_000) { model.state.value.rows.size == 1 }
                input
                    .assertIsEnabled()
                    .assertIsFocused()
                    .assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.EditableText,
                            AnnotatedString(""),
                        )
                    )
                assertTrue(f.handoffs.isEmpty())
                input.performTextInput("Second original")
                waitUntil(timeoutMillis = 5_000) { model.state.value.draft == "Second original" }
                onNodeWithContentDescription("Send message").performClick()
                waitUntil(timeoutMillis = 5_000) { model.state.value.rows.size == 2 }
                input.assertIsEnabled().assertIsFocused().performTextInput("Keep typing")
                runOnIdle { release.complete(Unit) }
                waitUntil(timeoutMillis = 10_000) { f.draft.value?.text == "Keep typing" }
                input.assertIsFocused().assertTextEquals("Keep typing")
                assertEquals(
                    listOf("First original", "Second original"),
                    f.handoffs.map { it.original.text },
                )
                assertEquals(
                    listOf("Second original", "First original"),
                    model.state.value.rows.map { it.bubble.text },
                )
            } finally {
                release.complete(Unit)
                runOnUiThread { store.clear() }
            }
        }
}
