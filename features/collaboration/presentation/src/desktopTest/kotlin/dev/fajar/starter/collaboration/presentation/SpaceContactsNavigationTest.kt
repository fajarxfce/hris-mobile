@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.navigation.*
import dev.fajar.starter.collaboration.presentation.spacecontacts.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import kotlin.test.*
import kotlinx.serialization.Serializable
import org.koin.compose.KoinIsolatedContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class SpaceContactsNavigationTest {
    @Test
    fun browseViewportAndSearchResultsReopenFromOneRetainedDirectory() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val h = ContactNavigationHarness()
            try {
                setContent { h.Content() }
                waitUntil(timeoutMillis = 10_000) { h.model?.state?.value?.contacts?.size == 100 }
                onAllNodes(hasScrollAction()).onFirst().performScrollToKey(chatId(1350))
                onNodeWithText("Colleague 050").assertIsDisplayed()
                val search =
                    onNodeWithText("Search contacts")
                        .fetchSemanticsNode()
                        .config[SemanticsActions.OnClick]
                runOnIdle { assertTrue(requireNotNull(search.action).invoke()) }
                onNode(hasSetTextAction()).performTextInput("Colleague 075")
                waitUntil(timeoutMillis = 5_000) {
                    h.model?.state?.value?.appliedQuery == "Colleague 075"
                }
                onNode(hasText("Colleague 075") and !hasSetTextAction()).assertExists()
                val back =
                    onNodeWithContentDescription("Back")
                        .fetchSemanticsNode()
                        .config[SemanticsActions.OnClick]
                runOnIdle { assertTrue(requireNotNull(back.action).invoke()) }
                onNodeWithText("Colleague 050").assertIsDisplayed()
                assertEquals("Colleague 075", h.model?.state?.value?.query)
                assertEquals(100, h.model?.state?.value?.contacts?.size)
                assertEquals(1, h.creations)
                assertEquals(1, h.contacts.starts)
                mainClock.autoAdvance = false
                val reopen =
                    onNodeWithText("Search contacts")
                        .fetchSemanticsNode()
                        .config[SemanticsActions.OnClick]
                runOnIdle { assertTrue(requireNotNull(reopen.action).invoke()) }
                var visible = false
                for (frame in 1..2) {
                    mainClock.advanceTimeByFrame()
                    if (onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()) {
                        onNode(hasText("Colleague 075") and !hasSetTextAction()).assertExists()
                        visible = true
                        break
                    }
                }
                assertTrue(
                    visible,
                    "Search and retained results must compose in the same destination frame",
                )
                assertEquals(1, h.contacts.starts)
            } finally {
                mainClock.autoAdvance = true
                runOnUiThread { h.shown = false }
                waitForIdle()
                runOnUiThread { h.close() }
            }
        }

    @Test
    fun aCapturedContactActionCannotCrossRootDenialAndReadmission() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val h = ContactNavigationHarness()
            try {
                setContent { h.Content() }
                waitUntil(timeoutMillis = 10_000) {
                    h.model?.state?.value?.visible == SpaceContactsSurface.Directory
                }
                waitUntil(timeoutMillis = 5_000) { h.model?.state?.value?.contacts?.size == 100 }
                val action =
                    onNodeWithText("Colleague 001")
                        .fetchSemanticsNode()
                        .config[SemanticsActions.OnClick]
                runOnIdle { h.deny() }
                waitUntil(timeoutMillis = 5_000) {
                    h.currentRoot == null && h.contacts.readers == 0
                }
                runOnIdle {
                    assertTrue(requireNotNull(action.action).invoke())
                    h.allow()
                }
                waitUntil(timeoutMillis = 5_000) {
                    h.creations == 2 &&
                        h.model?.state?.value?.contacts?.size == 100 &&
                        h.model?.state?.value?.visible == SpaceContactsSurface.Directory
                }
                runOnIdle { assertTrue(requireNotNull(action.action).invoke()) }
                waitForIdle()
                assertTrue(h.opened.isEmpty())
                val current =
                    onNodeWithText("Colleague 001")
                        .fetchSemanticsNode()
                        .config[SemanticsActions.OnClick]
                runOnIdle { assertTrue(requireNotNull(current.action).invoke()) }
                waitUntil(timeoutMillis = 5_000) { h.opened.isNotEmpty() }
                assertEquals(listOf(chatId(1301)), h.opened)
            } finally {
                runOnUiThread { h.shown = false }
                waitForIdle()
                runOnUiThread { h.close() }
            }
        }
}

@Serializable internal data object ContactDirectoryTestRoute

@Serializable internal data object ContactSearchTestRoute

private class ContactNavigationHarness : LifecycleOwner, ViewModelStoreOwner {
    val rootFixture = SpaceRootPresentationFixture()
    val contacts =
        SpaceContactsFixture().apply {
            facts.value =
                AppResult.Success(
                    CollaborationSpaceDirectorySnapshot(
                        checkpoint.copy(count = 100),
                        (1..100).map { index ->
                            contacts[0].copy(
                                accountId = chatId(1300 + index),
                                displayName = "Colleague " + index.toString().padStart(3, '0'),
                            )
                        },
                    )
                )
        }
    private val registry =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle
        get() = registry

    override val viewModelStore = ViewModelStore()
    private val owner = rootFixture.model().also { viewModelStore.put("root", it) }
    var shown by mutableStateOf(true)
    var currentRoot: SpaceChatRoot? = null
    var model: SpaceContactsViewModel? = null
    var creations = 0
    val opened = mutableListOf<String>()
    private val injection = koinApplication {
        modules(
            module {
                viewModel {
                    contacts.model().also {
                        model = it
                        creations++
                    }
                }
            }
        )
    }

    @Composable
    fun Content() {
        if (!shown) return
        KoinIsolatedContext(injection) {
            CompositionLocalProvider(
                LocalLifecycleOwner provides this,
                LocalViewModelStoreOwner provides this,
            ) {
                AppTheme {
                    SpaceChatRootContent(owner) { _, root ->
                        SideEffect { currentRoot = root }
                        if (root != null)
                            key(root) {
                                val navigation = rememberNavController()
                                NavHost(navigation, startDestination = ContactDirectoryTestRoute) {
                                    composable<ContactDirectoryTestRoute> {
                                        SpaceContactsContent(
                                            root,
                                            SpaceContactsSurface.Directory,
                                            opened::add,
                                            search = { _, _ ->
                                                AppSearchEntry(
                                                    "Search contacts",
                                                    { navigation.navigate(ContactSearchTestRoute) },
                                                )
                                            },
                                        )
                                    }
                                    composable<ContactSearchTestRoute> {
                                        SpaceContactsContent(
                                            root,
                                            SpaceContactsSurface.Search,
                                            opened::add,
                                            search = { state, onEvent ->
                                                Row(
                                                    Modifier.fillMaxWidth().padding(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    AppIconButton(
                                                        Icons.Regular.ArrowLeft,
                                                        "Back",
                                                        { navigation.popBackStack() },
                                                    )
                                                    AppSearchField(
                                                        state.query,
                                                        {
                                                            onEvent(
                                                                SpaceContactsEvent.QueryChanged(it)
                                                            )
                                                        },
                                                        "Search contacts",
                                                        "Clear search",
                                                        Modifier.weight(1f),
                                                        autoFocus = true,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

    fun deny() {
        rootFixture.thread.gate.value =
            AppResult.Success(
                CollaborationSpaceDirectoryAdmission(chatId(1399), rootFixture.thread.denial)
            )
    }

    fun allow() {
        rootFixture.thread.gate.value =
            AppResult.Success(CollaborationSpaceDirectoryAdmission(null, null))
    }

    fun close() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        injection.close()
    }
}
