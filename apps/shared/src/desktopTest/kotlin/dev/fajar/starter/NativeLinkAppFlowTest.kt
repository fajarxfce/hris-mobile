package dev.fajar.starter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.app.bootstrap.AppViewModel
import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.app.navigation.AppLinkChannel
import dev.fajar.starter.app.notifications.CollaborationNotificationPublicationResult
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.onboarding.domain.usecases.CompleteOnboarding
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.koin.dsl.module

/** Real application routes/Koin/storage with fictional HTTP/SDK; no native OS claim. */
class NativeLinkAppFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun typedNativeTapWaitsForResumeThenOpensTheThreadWithChatAsItsBackDestination() {
        val f = NotificationPublisherFixture()
        val repository = f.app.koin.get<WorkspaceRepository>()
        val links = AppLinkChannel()
        val owner = FlowOwner()
        var visible by mutableStateOf(true)
        try {
            runBlocking {
                f.initialize()
                assertEquals(listOf(f.app.first), f.app.server.collaboration.listedIds)
                f.app.koin.loadModules(
                    listOf(
                        module {
                            single<WorkspaceRepository> { repository }
                            single<NotificationPermissionSource> {
                                object : NotificationPermissionSource {
                                    override suspend fun check() = NotificationPermission.Granted

                                    override suspend fun request() = NotificationPermission.Granted
                                }
                            }
                            single<NotificationDisplaySource> {
                                object : NotificationDisplaySource {
                                    override suspend fun show(payload: NotificationPayload) = Unit
                                }
                            }
                            single<PushTokenSource> { UnavailablePushTokenSource() }
                        }
                    )
                )
                assertIs<AppResult.Success<*>>(f.app.koin.get<CompleteOnboarding>()())
                assertEquals(
                    AppResult.Success(CollaborationNotificationPublicationResult.Submitted),
                    f.publish(),
                )
            }
            f.app.koin.get<ApplicationCollaborationLifetime>().start()
            compose.setContent {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides owner,
                    LocalViewModelStoreOwner provides owner,
                ) {
                    if (visible)
                        Box(Modifier.size(420.dp, 760.dp)) {
                            StarterApp(f.app.container, links.links)
                        }
                }
            }
            waitFor("Welcome")
            val card = f.native.cards.values.single()
            compose.runOnIdle {
                links.receive(
                    ActionNotificationInput(
                        card.reference,
                        ActionNotificationKind.Open,
                        card.envelopes.getValue(ActionNotificationKind.Open),
                    )
                )
            }
            compose.waitUntil(15_000) { owner.app()?.state?.value?.pendingLink != null }
            compose.onNodeWithText("Fictional original for notification").assertDoesNotExist()
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            waitFor("Fictional original for notification")
            compose.onNodeWithText("Fictional colleague").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(15_000) {
                compose
                    .onAllNodesWithContentDescription("New chat")
                    .fetchSemanticsNodes()
                    .isNotEmpty() &&
                    compose.onAllNodesWithText("Fictional colleague").fetchSemanticsNodes().size ==
                        f.app.server.collaboration.listedIds.size
            }
            compose.onNodeWithContentDescription("New chat").assertIsDisplayed()
            compose.onNodeWithText("Saved replies").assertDoesNotExist()
            compose
                .onAllNodesWithText("Fictional colleague")
                .assertCountEquals(f.app.server.collaboration.listedIds.size)
            compose.onAllNodesWithText("Fictional colleague")[0].assertIsDisplayed()
            compose.onNodeWithText("Fictional original for notification").assertIsDisplayed()
            compose.onNode(hasText("Chat") and isSelectable()).assertIsSelected()
            compose.runOnIdle {
                assertNull(owner.app()?.state?.value?.pendingLink)
                assertTrue(f.app.sdk.enqueued.isEmpty())
            }
        } catch (failure: Throwable) {
            failure.printStackTrace()
            compose.mainClock.autoAdvance = false
            runCatching { println(compose.onRoot().printToString()) }
            throw failure
        } finally {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            compose.runOnIdle { owner.close() }
            links.close()
            runBlocking { f.close() }
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private class FlowOwner : LifecycleOwner, ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
        override val lifecycle =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.STARTED }

        fun app() =
            viewModelStore.keys().mapNotNull { viewModelStore[it] as? AppViewModel }.singleOrNull()

        fun close() {
            lifecycle.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
