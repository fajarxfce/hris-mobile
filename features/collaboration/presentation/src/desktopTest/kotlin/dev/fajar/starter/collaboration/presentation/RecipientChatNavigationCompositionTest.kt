@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@file:Suppress("DEPRECATION")

package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.navigation.*
import dev.fajar.starter.collaboration.presentation.recipient.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.koin.compose.KoinIsolatedContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class RecipientChatNavigationCompositionTest {
    @Test
    fun openingAndReopeningAPreparedRecipientKeepsTheSameLocalReaderAndBubbles() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val h = RecipientNavigationHarness()
            try {
                setContent { h.Content() }
                waitUntil(timeoutMillis = 10_000) { h.model?.state?.value?.rows?.size == 1 }
                val before = requireNotNull(h.model)
                assertFalse(before.state.value.visible)
                assertFalse(before.state.value.canSend)
                openContact()
                onNodeWithText("Retained recipient").assertIsDisplayed()
                assertSame(before, h.model)
                onNodeWithContentDescription("Back").performClick()
                openContact()
                onNodeWithText("Retained recipient").assertIsDisplayed()
                assertSame(before, h.model)
                assertEquals(1, h.creations)
                assertEquals(1, h.fixture.sourceStarts)
                assertEquals(0, h.fixture.metadataReads)
                assertEquals(0, h.fixture.thread.schedules)
            } finally {
                dispose(h)
            }
        }

    @Test
    fun sendShowsTheBubbleAndLaterTypingWhileBackWaitsOnlyForLocalPersistence() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val h = RecipientNavigationHarness()
            val release = CompletableDeferred<Unit>()
            try {
                h.fixture.editor.beforeHandoff = { release.await() }
                setContent { h.Content() }
                waitUntil(timeoutMillis = 10_000) { h.model?.state?.value?.editor?.ready == true }
                openContact()
                val input = onNodeWithContentDescription("Message")
                input.performClick().performTextInput("New offline message")
                onNodeWithContentDescription("Send message").performClick()
                onNodeWithText("New offline message").assertIsDisplayed()
                input.assertIsFocused().performTextInput("Later draft")
                assertTrue(h.fixture.editor.handed.isEmpty())
                onNodeWithContentDescription("Back").performClick()
                waitUntil(timeoutMillis = 5_000) { h.model?.state?.value?.leaving == true }
                onNodeWithText("New offline message").assertIsDisplayed()
                onNodeWithText("Open contact").assertDoesNotExist()
                runOnIdle { release.complete(Unit) }
                openContact()
                input.assertTextEquals("Later draft")
                onNodeWithText("New offline message").assertIsDisplayed()
                assertEquals(1, h.fixture.editor.handed.size)
                assertEquals(0, h.fixture.thread.schedules)
            } finally {
                release.complete(Unit)
                dispose(h)
            }
        }

    @Test
    fun selectionCopiesThroughTheActualRouteAndClearSelectionDoesNotNavigate() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val h = RecipientNavigationHarness()
            try {
                setContent { h.Content() }
                waitUntil(timeoutMillis = 10_000) { h.model?.state?.value?.rows?.size == 1 }
                openContact()
                onNodeWithText("Retained recipient").performTouchInput { longClick() }
                onNodeWithContentDescription("Copy").performClick()
                waitUntil(timeoutMillis = 5_000) { h.writes.size == 1 }
                assertEquals("Retained recipient", h.writes.single().text)
                onNodeWithContentDescription("Copy").assertDoesNotExist()
                onNodeWithContentDescription("Clear selection").assertDoesNotExist()
                onNodeWithText("Retained recipient").performTouchInput { longClick() }
                onNodeWithContentDescription("Clear selection").performClick()
                onNodeWithText("Retained recipient").assertIsDisplayed()
                onNodeWithContentDescription("Copy").assertDoesNotExist()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Open contact").assertIsDisplayed()
            } finally {
                dispose(h)
            }
        }

    @Test
    fun lifecycleStopCancelsTheActualClipboardReadAndRejectsItsLateResult() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val h = RecipientNavigationHarness()
            val release = CompletableDeferred<Unit>()
            var reading: Job? = null
            var finished = false
            try {
                setContent { h.Content() }
                waitUntil(timeoutMillis = 10_000) { h.model?.state?.value?.rows?.size == 1 }
                openContact()
                runOnIdle {
                    h.fixture.beforeMetadata = {
                        reading = currentCoroutineContext().job
                        try {
                            withContext(NonCancellable) { release.await() }
                        } finally {
                            finished = true
                        }
                    }
                }
                onNodeWithText("Retained recipient").performTouchInput { longClick() }
                onNodeWithContentDescription("Copy").performClick()
                waitUntil(timeoutMillis = 5_000) { reading != null }
                val captured = requireNotNull(reading)
                assertTrue(captured.isActive)
                runOnUiThread { h.registry.currentState = Lifecycle.State.CREATED }
                waitUntil(timeoutMillis = 2_000) { !captured.isActive }
                assertFalse(release.isCompleted)
                runOnIdle { release.complete(Unit) }
                waitUntil(timeoutMillis = 5_000) { finished }
                runOnIdle { assertTrue(h.writes.isEmpty()) }
            } finally {
                release.complete(Unit)
                dispose(h)
            }
        }

    @Test
    fun rootDenialDisposesTheRecipientAndReadmissionCreatesANewLocalLifetime() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val h = RecipientNavigationHarness()
            try {
                setContent { h.Content() }
                waitUntil(timeoutMillis = 10_000) { h.model?.state?.value?.rows?.size == 1 }
                openContact()
                onNodeWithText("Retained recipient").assertIsDisplayed()
                val oldRoot = requireNotNull(h.currentRoot)
                val oldModel = requireNotNull(h.model)
                runOnIdle { h.admit(false) }
                waitUntil(timeoutMillis = 5_000) {
                    h.currentRoot == null && h.fixture.sourceReaders == 0
                }
                assertFalse(oldRoot.isOpen)
                assertTrue(oldModel.state.value.rows.isEmpty())
                onNodeWithText("Retained recipient").assertDoesNotExist()
                runOnIdle { h.admit(true) }
                waitUntil(timeoutMillis = 10_000) {
                    h.currentRoot != null &&
                        h.creations == 2 &&
                        h.model?.state?.value?.rows?.size == 1
                }
                assertNotSame(oldRoot, h.currentRoot)
                assertNotSame(oldModel, h.model)
                assertFalse(requireNotNull(h.model).state.value.visible)
                assertFalse(requireNotNull(h.model).state.value.canSend)
                openContact()
                onNodeWithText("Retained recipient").assertIsDisplayed()
                assertEquals(1, h.fixture.sourceReaders)
                assertEquals(1, h.fixture.maxSourceReaders)
                assertEquals(0, h.fixture.thread.schedules)
            } finally {
                dispose(h)
            }
        }

    @Test
    fun changingTheCompanySubtreeKeepsTheGroupRecipientCache() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val h = RecipientNavigationHarness()
            try {
                setContent { h.Content() }
                waitUntil(timeoutMillis = 10_000) { h.model?.state?.value?.rows?.size == 1 }
                val root = h.currentRoot
                val model = h.model
                runOnIdle { h.company = "Company two" }
                onNodeWithText("Company two").assertIsDisplayed()
                openContact()
                onNodeWithText("Retained recipient").assertIsDisplayed()
                assertSame(root, h.currentRoot)
                assertSame(model, h.model)
                assertEquals(1, h.fixture.sourceStarts)
                assertEquals(1, h.creations)
            } finally {
                dispose(h)
            }
        }

    /** Desktop synthetic pointer delivery is not the UI thread required by NavController. */
    private fun ComposeUiTest.openContact() {
        val click =
            onNodeWithText("Open contact")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
        runOnUiThread { assertTrue(requireNotNull(click.action).invoke()) }
        waitForIdle()
    }

    private fun ComposeUiTest.dispose(h: RecipientNavigationHarness) {
        runOnUiThread { h.shown = false }
        waitForIdle()
        runOnUiThread { h.close() }
    }
}

@Serializable internal data object RecipientNavigationContacts

private class RecipientNavigationHarness : LifecycleOwner, ViewModelStoreOwner {
    val fixture =
        RecipientChatThreadViewModelFixture().apply {
            metadataAbsent = true
            val original =
                CollaborationSpaceRecipientSubmission(
                    CollaborationSpaceRecipientDraft(
                        peer,
                        chatId(2701),
                        "Retained recipient",
                        thread.base.now,
                    ),
                    chatId(2702),
                    thread.base.now,
                )
            editor.originals[original.id] = original
            editor.facts.value = AppResult.Success(original.nextDraft)
        }
    private val rootFixture = SpaceRootPresentationFixture()
    val registry =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle
        get() = registry

    override val viewModelStore = ViewModelStore()
    private val owner = rootFixture.model().also { viewModelStore.put("root", it) }
    var shown by mutableStateOf(true)
    var company by mutableStateOf("Company one")
    var currentRoot: SpaceChatRoot? = null
    var model: RecipientChatThreadViewModel? = null
    var creations = 0
    val writes = mutableListOf<AnnotatedString>()
    private val clipboard =
        object : ClipboardManager {
            override fun getText(): AnnotatedString? = writes.lastOrNull()

            override fun setText(annotatedString: AnnotatedString) {
                writes += annotatedString
            }
        }
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
        if (!shown) return
        KoinIsolatedContext(injection) {
            CompositionLocalProvider(
                LocalLifecycleOwner provides this,
                LocalViewModelStoreOwner provides this,
                LocalClipboardManager provides clipboard,
                LocalAppLanguage provides AppLanguage.English,
            ) {
                AppTheme(reduceMotion = true) {
                    SpaceChatRootContent(owner) { _, root ->
                        SideEffect { currentRoot = root }
                        Column(Modifier.fillMaxSize()) {
                            key(company) { AppText(company) }
                            if (root != null)
                                key(root) {
                                    val navigation = rememberNavController()
                                    NavHost(
                                        navigation,
                                        startDestination = RecipientNavigationContacts,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        composable<RecipientNavigationContacts> {
                                            val list = rememberLazyListState()
                                            PrepareVisibleSpaceChatRecipients(
                                                root.graph,
                                                listOf(fixture.peer),
                                                list,
                                            )
                                            LazyColumn(state = list) {
                                                item(key = fixture.peer) {
                                                    AppTextButton(
                                                        "Open contact",
                                                        {
                                                            navigation.navigate(
                                                                SpaceChatRecipientRoute(
                                                                    fixture.peer
                                                                )
                                                            )
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                        spaceChatThreadRoutes(root) { navigation.popBackStack() }
                                    }
                                }
                        }
                    }
                }
            }
        }
    }

    fun admit(allowed: Boolean) {
        val value =
            AppResult.Success(
                CollaborationSpaceDirectoryAdmission(
                    if (allowed) null else chatId(2799),
                    if (allowed) null else fixture.thread.denial,
                )
            )
        rootFixture.thread.gate.value = value
        fixture.thread.gate.value = value
    }

    fun close() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        injection.close()
    }
}
