@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.navigation.*
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatThreadViewModel
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppTheme
import kotlin.test.*
import kotlinx.serialization.Serializable
import org.koin.compose.KoinIsolatedContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class SpaceChatRootCompositionTest {
    @Test
    fun replacingTheCompanySubtreeKeepsTheRootAndPreparedLocalThread() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val h = RootCompositionHarness()
            try {
                setContent { h.Content() }
                waitUntil(timeoutMillis = 10_000) {
                    h.model?.state?.value?.rows?.size == 1 && h.currentRoot != null
                }
                val before = h.currentRoot
                val starts = h.fixture.thread.draftStarts
                runOnIdle { h.company = "Company two" }
                onNodeWithText("Company two").assertIsDisplayed()
                assertSame(before, h.currentRoot)
                assertEquals(starts, h.fixture.thread.draftStarts)
                assertEquals(1, h.creations)
            } finally {
                runOnUiThread { h.shown = false }
                waitForIdle()
                runOnUiThread { h.close() }
            }
        }

    @Test
    fun denialAndReadmissionReplaceTheRootEvenForIdenticalAccountCoordinates() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val h = RootCompositionHarness()
            try {
                setContent { h.Content() }
                waitUntil(timeoutMillis = 10_000) { h.currentRoot != null }
                val original = requireNotNull(h.currentRoot)
                runOnIdle { h.deny() }
                waitUntil(timeoutMillis = 5_000) { h.currentRoot == null }
                assertFalse(original.isOpen)
                runOnIdle { h.allow() }
                waitUntil(timeoutMillis = 5_000) { h.currentRoot != null }
                assertNotSame(original, h.currentRoot)
                assertEquals(original.access, h.currentRoot?.access)
            } finally {
                runOnUiThread { h.shown = false }
                waitForIdle()
                runOnUiThread { h.close() }
            }
        }

    @Test
    fun anOutgoingCompositionCannotWarmOrOpenAnotherModelAfterItsRootWasClosed() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val h = RootCompositionHarness()
            try {
                setContent { h.Content() }
                waitUntil(timeoutMillis = 10_000) {
                    h.model?.state?.value?.rows?.size == 1 && h.currentRoot != null
                }
                runOnIdle {
                    h.keepOutgoingRoot = true
                    h.outgoingRoot = h.currentRoot
                    h.deny()
                }
                waitUntil(timeoutMillis = 5_000) { h.currentRoot == null }
                assertFalse(requireNotNull(h.outgoingRoot).isOpen)
                runOnIdle { h.warm = false }
                waitForIdle()
                val open =
                    onNodeWithText("Open room")
                        .fetchSemanticsNode()
                        .config[SemanticsActions.OnClick]
                runOnIdle { assertTrue(requireNotNull(open.action).invoke()) }
                waitForIdle()
                onNodeWithText("Retained original 1").assertDoesNotExist()
                assertEquals(1, h.creations)
                assertEquals(0, h.fixture.thread.draftReaders)
            } finally {
                runOnUiThread { h.shown = false }
                waitForIdle()
                runOnUiThread { h.close() }
            }
        }
}

@Serializable internal data object RootCompositionInbox

private class RootCompositionHarness : LifecycleOwner, ViewModelStoreOwner {
    val fixture =
        SpaceRootPresentationFixture().apply {
            thread.entries.value =
                listOf(CollaborationComposerOutboxEntry(thread.submission(), false))
        }
    private val registry =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle
        get() = registry

    override val viewModelStore = ViewModelStore()
    private val owner = fixture.model().also { viewModelStore.put("root", it) }
    var shown by mutableStateOf(true)
    var company by mutableStateOf("Company one")
    var warm by mutableStateOf(true)
    var keepOutgoingRoot by mutableStateOf(false)
    var outgoingRoot: SpaceChatRoot? by mutableStateOf(null)
    var currentRoot: SpaceChatRoot? = null
    var creations = 0
    var model: SpaceChatThreadViewModel? = null
    private val injection = koinApplication {
        modules(
            module {
                viewModel {
                    fixture.thread.model().also {
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
                        val visibleRoot = if (keepOutgoingRoot) outgoingRoot else root
                        Column {
                            key(company) { AppText(company) }
                            if (visibleRoot != null)
                                key(visibleRoot) {
                                    val navigation = rememberNavController()
                                    NavHost(navigation, startDestination = RootCompositionInbox) {
                                        composable<RootCompositionInbox> {
                                            val list = rememberLazyListState()
                                            PrepareVisibleSpaceChatThreads(
                                                visibleRoot.graph,
                                                listOf(fixture.thread.conversationId),
                                                list,
                                                warm,
                                            )
                                            LazyColumn(state = list) {
                                                item(key = fixture.thread.conversationId) {
                                                    AppTextButton(
                                                        "Open room",
                                                        {
                                                            navigation.navigate(
                                                                SpaceChatThreadRoute(
                                                                    fixture.thread.conversationId
                                                                )
                                                            )
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                        spaceChatThreadRoutes(visibleRoot.graph) {
                                            navigation.popBackStack()
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }
    }

    fun deny() {
        fixture.thread.gate.value =
            AppResult.Success(
                CollaborationSpaceDirectoryAdmission(chatId(980), fixture.thread.denial)
            )
    }

    fun allow() {
        fixture.thread.gate.value =
            AppResult.Success(CollaborationSpaceDirectoryAdmission(null, null))
    }

    fun close() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        injection.close()
    }
}
