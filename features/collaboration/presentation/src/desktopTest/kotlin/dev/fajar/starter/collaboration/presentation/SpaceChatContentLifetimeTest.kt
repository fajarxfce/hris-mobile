@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@file:Suppress("DEPRECATION")

package dev.fajar.starter.collaboration.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.fajar.starter.collaboration.domain.entities.CollaborationComposerOutboxEntry
import dev.fajar.starter.collaboration.presentation.navigation.SpaceChatThreadContent
import dev.fajar.starter.collaboration.presentation.spacethread.*
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import kotlin.test.*
import kotlinx.coroutines.*
import org.koin.compose.KoinIsolatedContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class SpaceChatContentLifetimeTest {
    @Test
    fun stoppingTheActualContentCancelsItsClipboardReadBeforeTheActionDeadline() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val f = SpaceChatThreadFixture()
            f.entries.value = listOf(CollaborationComposerOutboxEntry(f.submission(), false))
            val owner = SpacePageOwner()
            var model: SpaceChatThreadViewModel? = null
            val injection = koinApplication {
                modules(module { viewModel { f.model().also { model = it } } })
            }
            val release = CompletableDeferred<Unit>()
            var reading: Job? = null
            var finished = false
            val writes = mutableListOf<AnnotatedString>()
            val clipboard =
                object : ClipboardManager {
                    override fun getText(): AnnotatedString? = writes.lastOrNull()

                    override fun setText(annotatedString: AnnotatedString) {
                        writes += annotatedString
                    }
                }
            try {
                setContent {
                    KoinIsolatedContext(injection) {
                        CompositionLocalProvider(
                            LocalLifecycleOwner provides owner,
                            LocalClipboardManager provides clipboard,
                            LocalAppLanguage provides AppLanguage.English,
                        ) {
                            AppTheme(reduceMotion = true) {
                                SpaceChatThreadContent(f.access, f.conversationId, owner, {})
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = 10_000) { model?.state?.value?.rows?.size == 1 }
                runOnIdle {
                    f.beforeMetadata = {
                        reading = currentCoroutineContext().job
                        try {
                            withContext(NonCancellable) { release.await() }
                        } finally {
                            finished = true
                        }
                    }
                }
                onNodeWithText("Retained original 1").performTouchInput { longClick() }
                onNodeWithContentDescription("Copy").performClick()
                waitUntil(timeoutMillis = 5_000) { reading != null }
                assertTrue(requireNotNull(reading).isActive)
                runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
                // The domain deadline is 5 seconds. This must be lifecycle cancellation instead.
                waitUntil(timeoutMillis = 2_000) { reading?.isActive == false }
                assertFalse(release.isCompleted)
                runOnIdle { release.complete(Unit) }
                waitUntil(timeoutMillis = 5_000) { finished }
                runOnIdle { assertTrue(writes.isEmpty()) }
            } finally {
                release.complete(Unit)
                runOnUiThread {
                    owner.registry.currentState = Lifecycle.State.DESTROYED
                    owner.viewModelStore.clear()
                }
                injection.close()
            }
        }
}

private class SpacePageOwner : LifecycleOwner, ViewModelStoreOwner {
    val registry =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle
        get() = registry

    override val viewModelStore = ViewModelStore()
}
