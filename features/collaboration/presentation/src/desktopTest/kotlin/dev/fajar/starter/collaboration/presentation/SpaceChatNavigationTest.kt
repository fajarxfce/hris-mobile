@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.fajar.starter.collaboration.domain.entities.CollaborationComposerOutboxEntry
import dev.fajar.starter.collaboration.presentation.navigation.*
import dev.fajar.starter.collaboration.presentation.spacethread.*
import dev.fajar.starter.designsystem.components.AppTextButton
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import kotlin.test.*
import kotlinx.serialization.Serializable
import org.koin.compose.KoinIsolatedContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class SpaceChatNavigationTest {
    @Test
    fun preparedRowsExistInTheFirstComposedDestinationWithoutAnotherModelOrReader() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val h = SpaceNavigationHarness()
            try {
                setContent { h.Content() }
                waitUntil(timeoutMillis = 10_000) { h.model?.state?.value?.rows?.size == 1 }
                val prepared = h.model
                mainClock.autoAdvance = false
                val openRoom =
                    onNodeWithText("Open Group room")
                        .fetchSemanticsNode()
                        .config[SemanticsActions.OnClick]
                runOnIdle { assertTrue(requireNotNull(openRoom.action).invoke()) }
                // The first clock tick can only start NavHost's transition. Inspect the
                // first frame containing the actual destination, never wait for message data.
                var firstDestinationFrame: Int? = null
                for (frame in 1..2) {
                    mainClock.advanceTimeByFrame()
                    if (
                        onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()
                    ) {
                        onNodeWithText("Retained original 1").assertExists()
                        firstDestinationFrame = frame
                        break
                    }
                }
                assertNotNull(firstDestinationFrame, "Destination must compose within two frames")
                println(
                    "Group navigation scenario 1: first destination frame $firstDestinationFrame has retained rows"
                )
                assertSame(prepared, h.model)
                assertEquals(1, h.creations)
                assertEquals(1, h.fixture.draftStarts)
                assertEquals(1, h.fixture.content.bindingReaders)
                assertEquals(0, h.fixture.schedules)
            } finally {
                mainClock.autoAdvance = true
                waitForIdle()
                runOnUiThread { h.close() }
            }
        }

    @Test
    fun reopeningUsesUpdatedIdleRowsAndTheSameDraftInItsFirstComposedDestination() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val h = SpaceNavigationHarness()
            try {
                setContent { h.Content() }
                waitUntil(timeoutMillis = 10_000) { h.model?.state?.value?.rows?.size == 1 }
                val openRoom =
                    onNodeWithText("Open Group room")
                        .fetchSemanticsNode()
                        .config[SemanticsActions.OnClick]
                runOnIdle { assertTrue(requireNotNull(openRoom.action).invoke()) }
                onNodeWithContentDescription("Message")
                    .performClick()
                    .performTextInput("Draft stays")
                waitUntil(timeoutMillis = 5_000) { h.model?.state?.value?.draft == "Draft stays" }
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Open Group room").assertIsDisplayed()
                waitUntil(timeoutMillis = 5_000) { h.model?.state?.value?.leaving == false }
                runOnIdle {
                    h.fixture.entries.value =
                        listOf(CollaborationComposerOutboxEntry(h.fixture.submission(2), false))
                }
                waitUntil(timeoutMillis = 5_000) {
                    h.model?.state?.value?.rows?.any { it.bubble.text == "Retained original 2" } ==
                        true
                }
                mainClock.autoAdvance = false
                val reopenRoom =
                    onNodeWithText("Open Group room")
                        .fetchSemanticsNode()
                        .config[SemanticsActions.OnClick]
                runOnIdle { assertTrue(requireNotNull(reopenRoom.action).invoke()) }
                // The first clock tick can only start NavHost's transition. Inspect the
                // first frame containing the actual destination, never wait for message data.
                var firstDestinationFrame: Int? = null
                for (frame in 1..2) {
                    mainClock.advanceTimeByFrame()
                    if (
                        onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()
                    ) {
                        onNodeWithText("Retained original 2").assertExists()
                        firstDestinationFrame = frame
                        break
                    }
                }
                assertNotNull(firstDestinationFrame, "Destination must compose within two frames")
                println(
                    "Group navigation scenario 2: first destination frame $firstDestinationFrame has retained rows"
                )
                onNodeWithContentDescription("Message").assertTextEquals("Draft stays")
                assertEquals(1, h.creations)
                assertEquals(1, h.fixture.draftStarts)
                assertEquals(1, h.fixture.content.bindingReaders)
                assertEquals("Draft stays", h.fixture.draft.value?.text)
                assertEquals(0, h.fixture.schedules)
            } finally {
                mainClock.autoAdvance = true
                waitForIdle()
                runOnUiThread { h.close() }
            }
        }
}

@Serializable internal data object SpaceNavigationTestInbox

/** Actual Koin/Content/NavHost lifetime, with the same local use cases as the Group screen. */
private class SpaceNavigationHarness : LifecycleOwner, ViewModelStoreOwner {
    val fixture =
        SpaceChatThreadFixture().apply {
            entries.value = listOf(CollaborationComposerOutboxEntry(submission(), false))
        }
    private val registry =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle
        get() = registry

    override val viewModelStore = ViewModelStore()
    private val owner =
        SpaceChatNavigationOwner(fixture.access).also { viewModelStore.put("group-navigation", it) }
    var model: SpaceChatThreadViewModel? = null
        private set

    var creations = 0
        private set

    private val injection = koinApplication {
        modules(
            module {
                viewModel {
                    fixture.model().also {
                        model = it
                        creations++
                    }
                }
            }
        )
    }

    @Composable
    fun Content() {
        KoinIsolatedContext(injection) {
            CompositionLocalProvider(
                LocalLifecycleOwner provides this,
                LocalViewModelStoreOwner provides this,
                LocalAppLanguage provides AppLanguage.English,
            ) {
                AppTheme {
                    val navigation = rememberNavController()
                    NavHost(navigation, startDestination = SpaceNavigationTestInbox) {
                        composable<SpaceNavigationTestInbox> {
                            val list = rememberLazyListState()
                            PrepareVisibleSpaceChatThreads(
                                owner,
                                listOf(fixture.conversationId),
                                list,
                            )
                            LazyColumn(state = list) {
                                item(key = fixture.conversationId) {
                                    AppTextButton(
                                        "Open Group room",
                                        {
                                            navigation.navigate(
                                                SpaceChatThreadRoute(fixture.conversationId)
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        spaceChatThreadRoutes(owner) { navigation.popBackStack() }
                    }
                }
            }
        }
    }

    fun close() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        injection.close()
    }
}
