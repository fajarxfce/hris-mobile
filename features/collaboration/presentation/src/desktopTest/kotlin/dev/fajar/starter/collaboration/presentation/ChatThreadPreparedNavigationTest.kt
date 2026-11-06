@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter.collaboration.presentation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.navigation.createGraph
import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalList
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.presentation.inbox.ChatInboxState
import dev.fajar.starter.collaboration.presentation.navigation.ChatThreadViewModelStores
import dev.fajar.starter.collaboration.presentation.navigation.PrepareVisibleChatThreads
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppTheme
import kotlin.test.*
import org.junit.Test
import org.koin.compose.KoinIsolatedContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class ChatThreadPreparedNavigationTest {
    @Test
    fun visibleConversationUsesThePreparedModelDuringItsFirstNavigationTransition() =
        runComposeUiTest {
            val compose = this
            val owner = PreparedNavigationOwner()
            val fixture = ChatThreadFixture()
            fixture.historyRows.value = listOf(fixture.original())
            val created = mutableListOf<ChatThreadViewModel>()
            val injection = koinApplication {
                modules(module { viewModel { fixture.model().also(created::add) } })
            }
            lateinit var controller: NavHostController
            val access = fixture.setup.owner.localListAccess()
            try {
                setContent {
                    KoinIsolatedContext(injection) {
                        CompositionLocalProvider(
                            LocalLifecycleOwner provides owner,
                            LocalViewModelStoreOwner provides owner,
                        ) {
                            controller = rememberNavController()
                            val graph = remember {
                                controller
                                    .createGraph(startDestination = RetainedRoomRoot) {
                                        composable<RetainedRoomRoot> { entry ->
                                            val parent =
                                                remember(entry) {
                                                    controller.getBackStackEntry("prepared-rooms")
                                                }
                                            val stores =
                                                viewModel(viewModelStoreOwner = parent) {
                                                    ChatThreadViewModelStores()
                                                }
                                            val list = rememberLazyListState()
                                            PrepareVisibleChatThreads(
                                                "selection",
                                                ChatInboxState(
                                                    page =
                                                        CollaborationLocalList(
                                                            null,
                                                            listOf(fixture.summary),
                                                            null,
                                                        ),
                                                    owner = access,
                                                    loading = false,
                                                ),
                                                list,
                                                stores,
                                            )
                                            LazyColumn(state = list) {
                                                item(key = fixture.conversationId) {
                                                    AppText("Conversation in list")
                                                }
                                            }
                                        }
                                        composable<RetainedRoomRoute> { entry ->
                                            val parent =
                                                remember(entry) {
                                                    controller.getBackStackEntry("prepared-rooms")
                                                }
                                            val stores =
                                                viewModel(viewModelStoreOwner = parent) {
                                                    ChatThreadViewModelStores()
                                                }
                                            val room =
                                                viewModel(viewModelStoreOwner = entry) {
                                                    stores.open(fixture.conversationId)
                                                }
                                            val vm =
                                                koinViewModel<ChatThreadViewModel>(
                                                    viewModelStoreOwner = room,
                                                    parameters = {
                                                        parametersOf(
                                                            "selection",
                                                            fixture.conversationId,
                                                        )
                                                    },
                                                )
                                            room.bind(vm)
                                            val state by vm.state.collectAsStateWithLifecycle()
                                            LifecycleStartEffect(vm) {
                                                vm.onEvent(ChatThreadEvent.Entered(access))
                                                onStopOrDispose {
                                                    vm.onEvent(ChatThreadEvent.Stopped)
                                                }
                                            }
                                            AppText(
                                                state.rows.firstOrNull()?.text
                                                    ?: "Waiting for local content"
                                            )
                                        }
                                    }
                                    .apply { route = "prepared-rooms" }
                            }
                            AppTheme {
                                Box(Modifier.size(360.dp, 600.dp)) {
                                    NavHost(
                                        controller,
                                        graph,
                                        enterTransition = { fadeIn(tween(180)) },
                                        exitTransition = { fadeOut(tween(180)) },
                                    )
                                }
                            }
                        }
                    }
                }
                onNodeWithText("Conversation in list").assertExists()
                waitUntil(timeoutMillis = 10_000) {
                    created.singleOrNull()?.state?.value?.rows?.isNotEmpty() == true
                }
                runOnIdle {
                    assertEquals(0, fixture.timelineOpens)
                    assertTrue(fixture.readState.marks.isEmpty())
                }
                mainClock.autoAdvance = false
                runOnIdle { controller.navigate(RetainedRoomRoute(fixture.conversationId)) }
                mainClock.advanceTimeBy(32)
                onNodeWithText("Saved conversation").assertIsDisplayed()
                onNodeWithText("Waiting for local content").assertDoesNotExist()
                runOnIdle {
                    assertEquals(1, created.size)
                    assertEquals(1, fixture.requestedWindows.size)
                    assertNotEquals(
                        Lifecycle.State.RESUMED,
                        controller.currentBackStackEntry?.lifecycle?.currentState,
                    )
                }
            } finally {
                mainClock.autoAdvance = true
                compose.runOnUiThread {
                    owner.close()
                    injection.close()
                }
            }
        }
}

private class PreparedNavigationOwner : ViewModelStoreOwner, LifecycleOwner {
    override val viewModelStore = ViewModelStore()
    override val lifecycle =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }

    fun close() {
        lifecycle.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
