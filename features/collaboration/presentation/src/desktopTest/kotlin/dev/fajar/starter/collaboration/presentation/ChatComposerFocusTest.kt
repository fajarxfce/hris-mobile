@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatThreadPage
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect

class ChatComposerFocusTest {
    @Test
    fun sendingKeepsInputFocusWhileTheActualViewModelWaitsForLocalStorage() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val fixture = ChatThreadFixture()
            val release = CompletableDeferred<Unit>()
            fixture.beforeComposerHandoff = { release.await() }
            val model = fixture.model()
            val store = ViewModelStore().also { it.put("thread", model) }
            try {
                setContent {
                    val state by model.state.collectAsState()
                    LaunchedEffect(model) { model.onEvent(ChatThreadEvent.Entered()) }
                    LaunchedEffect(model) { model.effects.collect() }
                    CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                        AppTheme(reduceMotion = true) {
                            ChatThreadPage(state, rememberLazyListState(), model::onEvent)
                        }
                    }
                }
                waitUntil(timeoutMillis = 10_000) { model.state.value.canCompose }
                val input = onNodeWithContentDescription("Message")
                input.performClick().performTextInput("First original")
                waitUntil(timeoutMillis = 5_000) { model.state.value.draft == "First original" }
                onNodeWithContentDescription("Send message").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    model.state.value.rows.any { it.text == "First original" }
                }
                input
                    .assertIsEnabled()
                    .assertIsFocused()
                    .assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.EditableText,
                            AnnotatedString(""),
                        )
                    )
                assertTrue(fixture.composerWrites.isEmpty())
                input.performTextInput("Second original")
                waitUntil(timeoutMillis = 5_000) { model.state.value.draft == "Second original" }
                onNodeWithContentDescription("Send message").performClick()
                waitUntil(timeoutMillis = 5_000) { model.state.value.rows.size == 2 }
                input.assertIsEnabled().assertIsFocused().performTextInput("Keep typing")
                waitUntil(timeoutMillis = 5_000) { model.state.value.draft == "Keep typing" }
                runOnIdle { release.complete(Unit) }
                waitUntil(timeoutMillis = 10_000) { fixture.draftSlot.value?.text == "Keep typing" }
                input.assertIsEnabled().assertIsFocused().assertTextEquals("Keep typing")
                assertEquals(
                    listOf("First original", "Second original"),
                    fixture.composerWrites.map { it.original.text },
                )
                assertEquals(
                    listOf("Second original", "First original"),
                    model.state.value.rows.map { it.text },
                )
            } finally {
                release.complete(Unit)
                runOnUiThread { store.clear() }
            }
        }
}
