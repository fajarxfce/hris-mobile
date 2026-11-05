package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatThreadPage
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class ChatThreadLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun uncachedHistoryKeepsComposerAvailableDuringSyncAndAtLargeText() {
        val events = mutableListOf<ChatThreadEvent>()
        var fontScale by mutableStateOf(1f)
        var state by
            mutableStateOf(
                ChatThreadState(
                    title = "Dewi Kartika",
                    connection = ChatThreadConnection.CONNECTED,
                    loading = false,
                    draftReady = true,
                    hasOlder = true,
                )
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(320.dp, 640.dp).testTag("frame")) {
                        ChatThreadPage(state, rememberLazyListState(), events::add)
                    }
                }
            }
        }
        screenshot("chat-uncached-history")
        compose.runOnIdle { fontScale = 2f }
        compose.onNodeWithText("Pesan akan muncul di sini.").assertIsDisplayed()
        compose.onNodeWithText("Tulis pesan pertama.").assertDoesNotExist()
        compose.onNodeWithText("Pesan sebelumnya").assertDoesNotExist()
        compose.onNodeWithText("Muat pesan").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pesan").assertIsDisplayed().assertIsEnabled()
        screenshot("chat-uncached-history-large-text")
        compose.onNodeWithContentDescription("Pesan").performTextInput("Halo")
        compose.runOnIdle {
            assertEquals(listOf<ChatThreadEvent>(ChatThreadEvent.DraftChanged("Halo")), events)
            state = state.copy(loadingOlder = true, syncing = true)
        }
        compose.onNodeWithText("Muat pesan").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pesan").assertIsEnabled()
        compose.onNodeWithText("Tulis pesan pertama.").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(loadingOlder = false, syncing = false, hasOlder = false)
        }
        compose.onNodeWithText("Muat pesan").assertDoesNotExist()
        compose.onNodeWithText("Tulis pesan pertama.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pesan").assertIsEnabled()
        screenshot("chat-confirmed-empty-large-text")
    }

    @Test
    fun queuedMessageCancelActionRemainsReachableAtNarrowLargeText() {
        val events = mutableListOf<ChatThreadEvent>()
        var menuOpen by mutableStateOf(false)
        val queued =
            row("queued", "Pesan ini masih menunggu koneksi.", ChatMessageOwnership.SELF)
                .copy(
                    localId = "queued",
                    eventId = null,
                    delivery = CollaborationMessageDelivery.PENDING,
                    canCancel = true,
                )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(320.dp, 640.dp).testTag("frame")) {
                        ChatThreadPage(
                            ChatThreadState(
                                title = "Dewi Kartika",
                                rows = listOf(queued),
                                selecting = true,
                                selectedMessages = listOf(queued),
                                loading = false,
                                draftReady = true,
                                selectionMenuOpen = menuOpen,
                            ),
                            rememberLazyListState(),
                            { event ->
                                events += event
                                if (event == ChatThreadEvent.SelectionMenuOpened) menuOpen = true
                                if (event == ChatThreadEvent.SelectionMenuDismissed)
                                    menuOpen = false
                            },
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Tindakan lainnya").performClick()
        compose
            .onNodeWithText("Batalkan kirim")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithContentDescription("Batal pilih").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pesan").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(
                listOf<ChatThreadEvent>(
                    ChatThreadEvent.SelectionMenuOpened,
                    ChatThreadEvent.SelectionMenuDismissed,
                    ChatThreadEvent.CancelRequested("queued"),
                ),
                events,
            )
        }
        screenshot("chat-cancel-queued-large-text")
    }

    @Test
    fun cancellationDialogRetainsReachableControlsAndDoesNotConfirmTwiceWhileBusy() {
        val events = mutableListOf<ChatThreadEvent>()
        var dialog by
            mutableStateOf(ChatCancellationDialog("Cuplikan pesan yang dipilih. ".repeat(150)))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    ChatThreadPage(
                        ChatThreadState(
                            title = "Dewi Kartika",
                            loading = false,
                            cancellationDialog = dialog,
                        ),
                        rememberLazyListState(),
                        { event ->
                            events += event
                            if (event == ChatThreadEvent.CancellationConfirmed)
                                dialog = dialog.copy(busy = true)
                        },
                    )
                }
            }
        }
        val action = compose.onNode(hasText("Batalkan kirim") and hasClickAction())
        action.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        action.assertIsNotEnabled()
        compose.onNodeWithContentDescription("Tutup").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf<ChatThreadEvent>(ChatThreadEvent.CancellationConfirmed), events)
            dialog =
                dialog.copy(
                    busy = false,
                    failure =
                        dev.fajar.starter.common.result.Failure(
                            dev.fajar.starter.common.result.FailureKind.Storage,
                            "Unavailable",
                        ),
                )
        }
        compose
            .onNode(hasText("Coba lagi") and hasClickAction())
            .assertIsDisplayed()
            .assertIsEnabled()
        Image.makeFromBitmap(compose.onNode(isDialog()).captureToImage().asSkiaBitmap()).use { image
            ->
            checkNotNull(image.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/chat-cancellation-dialog-large-text.png")
                    .writeBytes(data.bytes)
            }
        }
        compose.runOnIdle { dialog = dialog.copy(unavailable = true) }
        compose.onNode(hasText("Coba lagi") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Tutup") and hasClickAction()).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ChatThreadEvent.CancellationDismissed, events.last()) }
    }

    @Test
    fun draftReadAndCompactFailureKeepInputAndRetryUsableAtLargeText() {
        val events = mutableListOf<ChatThreadEvent>()
        var state by
            mutableStateOf(
                ChatThreadState(title = "Dewi Kartika", loading = false, draft = "Draf tersimpan")
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(320.dp, 640.dp).testTag("frame")) {
                        ChatThreadPage(state, rememberLazyListState(), { events += it })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Pesan").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle {
            state =
                state.copy(
                    draftReady = true,
                    draftFailure =
                        dev.fajar.starter.common.result.Failure(
                            dev.fajar.starter.common.result.FailureKind.Storage,
                            "Unavailable",
                        ),
                )
        }
        compose.onNodeWithContentDescription("Pesan").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Draf belum tersimpan").assertIsDisplayed()
        compose.onNodeWithText("Coba lagi").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Kembali").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf<ChatThreadEvent>(ChatThreadEvent.DraftRetryRequested), events)
        }
        screenshot("chat-draft-failure-large-text")
    }

    @Test
    fun narrowLargeTextKeepsComposerAndBackReachableWhileMessagesScroll() {
        val events = mutableListOf<ChatThreadEvent>()
        val messages = rows()
        var state by
            mutableStateOf(
                ChatThreadState(
                    draftReady = true,
                    title = "Dewi Kartika",
                    rows =
                        messages.take(2) +
                            (1..24).map {
                                row(
                                    "history-$it",
                                    "Riwayat percakapan $it",
                                    ChatMessageOwnership.COLLEAGUE,
                                )
                            } +
                            messages.last(),
                    loading = false,
                    draft = "Baik, terima kasih.",
                )
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(320.dp, 640.dp).testTag("frame")) {
                        ChatThreadPage(state, rememberLazyListState(), { events += it })
                    }
                }
            }
        }
        val back = compose.onNodeWithContentDescription("Kembali")
        val bounds =
            back
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .fetchSemanticsNode()
                .boundsInRoot
        val composer = compose.onNodeWithContentDescription("Pesan").assertIsDisplayed()
        val composerBounds = composer.fetchSemanticsNode().boundsInRoot
        compose
            .onNodeWithContentDescription("Kirim pesan")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        screenshot("chat-thread-indonesian-large-text")
        // Lazy items outside the composed window have no node to performScrollTo on.
        val oldMessage = "Pesan lama untuk pengujian."
        compose.onNodeWithText(oldMessage).assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(oldMessage))
        compose.onNodeWithText(oldMessage).assertIsDisplayed()
        assertEquals(bounds, back.fetchSemanticsNode().boundsInRoot)
        assertEquals(composerBounds, composer.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        screenshot("chat-thread-indonesian-large-text-scrolled")
        compose.onNodeWithContentDescription("Kirim pesan").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf<ChatThreadEvent>(ChatThreadEvent.SendRequested), events)
            state = state.copy(submissionPending = true)
        }
        compose.onNodeWithContentDescription("Pesan").assertIsNotEnabled()
    }

    @Test
    fun composerForwardsExactTextAndUsesSendActionWithoutMovingFocus() {
        val events = mutableListOf<ChatThreadEvent>()
        var state by
            mutableStateOf(
                ChatThreadState(
                    draftReady = true,
                    title = "Ari Pratama",
                    loading = false,
                    connection = ChatThreadConnection.CONNECTED,
                    rows = rows(),
                )
            )
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                AppTheme {
                    Box(Modifier.size(390.dp, 780.dp).testTag("frame")) {
                        ChatThreadPage(
                            state,
                            rememberLazyListState(),
                            { event ->
                                events += event
                                if (event is ChatThreadEvent.DraftChanged)
                                    state = state.copy(draft = event.text)
                            },
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Message").performTextInput("  review\nplease  ")
        compose.onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
        screenshot("chat-thread-english")
        compose.runOnIdle {
            assertEquals("  review\nplease  ", state.draft)
            assertEquals(ChatThreadEvent.SendRequested, events.last())
        }
    }

    @Test
    fun deletedAndUndecryptableRowsNeverRenderTheirRawBody() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                AppTheme {
                    ChatThreadPage(
                        ChatThreadState(
                            draftReady = true,
                            title = "Ari",
                            loading = false,
                            rows =
                                listOf(
                                    row(
                                            "deleted",
                                            "private old text",
                                            ChatMessageOwnership.COLLEAGUE,
                                        )
                                        .copy(kind = CollaborationMessageKind.REDACTED),
                                    row(
                                            "encrypted",
                                            "opaque ciphertext",
                                            ChatMessageOwnership.UNKNOWN,
                                        )
                                        .copy(kind = CollaborationMessageKind.UNABLE_TO_DECRYPT),
                                ),
                        ),
                        rememberLazyListState(),
                        {},
                    )
                }
            }
        }
        compose.onNodeWithText("private old text").assertDoesNotExist()
        compose.onNodeWithText("opaque ciphertext").assertDoesNotExist()
        compose.onNodeWithText("Message deleted").assertIsDisplayed()
        compose.onNodeWithText("This message cannot be decrypted yet.").assertIsDisplayed()
    }

    @Test
    fun olderLoadingKeepsCompositionAvailableAndIncompleteHistoryNoticeReadable() {
        val events = mutableListOf<ChatThreadEvent>()
        var state by
            mutableStateOf(
                ChatThreadState(
                    draftReady = true,
                    title = "Ari Pratama",
                    rows = rows(),
                    loading = false,
                    loadingOlder = true,
                    hasOlder = true,
                    connection = ChatThreadConnection.CONNECTED,
                    historyIncomplete = true,
                )
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(320.dp, 640.dp).testTag("frame")) {
                        ChatThreadPage(state, rememberLazyListState(), { events += it })
                    }
                }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Pesan sebelumnya"))
        compose.onNodeWithText("Pesan sebelumnya").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Pesan").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { state = state.copy(loadingOlder = false) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Pesan sebelumnya"))
        compose
            .onNodeWithText("Pesan sebelumnya")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Sebagian pesan sebelumnya belum tersedia."))
        compose
            .onNodeWithText("Sebagian pesan sebelumnya belum tersedia.")
            .performScrollTo()
            .assertIsDisplayed()
        screenshot("chat-thread-history-large-text")
        compose.runOnIdle {
            assertEquals(listOf<ChatThreadEvent>(ChatThreadEvent.OlderRequested), events)
        }
    }

    @Test
    fun historicalNavigationKeepsLargeTextComposerReachableAndPreservesAnOverlappingRow() {
        val events = mutableListOf<ChatThreadEvent>()
        val messages =
            (300 downTo 1).map {
                row("row-$it", "Pesan $it", ChatMessageOwnership.COLLEAGUE)
                    .copy(timestamp = it.toLong())
            }
        var state by
            mutableStateOf(
                ChatThreadState(
                    draftReady = true,
                    title = "Dewi Kartika",
                    loading = false,
                    rows = messages.take(150),
                    hasOlder = true,
                    draft = "Draft tetap ada",
                )
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(320.dp, 640.dp).testTag("frame")) {
                        ChatThreadPage(state, rememberLazyListState(), { events += it })
                    }
                }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(149)
        val anchor = compose.onNodeWithText("Pesan 151").assertIsDisplayed()
        compose.runOnIdle {
            state =
                state.copy(
                    rows = messages.drop(100).take(150),
                    browsingHistory = true,
                    hasNewer = true,
                )
        }
        // The overlapping key remains visible after the head is evicted; no reset to index zero.
        anchor.assertIsDisplayed()
        compose.onNodeWithContentDescription("Pesan").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Kirim pesan").assertIsDisplayed()
        compose
            .onNodeWithText("Ke pesan terbaru")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("Pesan lebih baru").assertIsDisplayed().performClick()
        screenshot("chat-thread-window-large-text")
        compose.runOnIdle {
            assertEquals(
                listOf(ChatThreadEvent.LatestRequested, ChatThreadEvent.NewerRequested),
                events,
            )
            state = state.copy(loading = true)
        }
        compose.onNodeWithText("Pesan lebih baru").assertIsNotEnabled()
        compose.onNodeWithText("Ke pesan terbaru").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(ChatThreadEvent.LatestRequested, events.last()) }
        compose.onNodeWithContentDescription("Pesan").assertIsEnabled()
    }

    private fun rows() =
        listOf(
            row("latest", "Baik, dokumen klaimnya sudah saya periksa.", ChatMessageOwnership.SELF),
            row("peer", "Bisa cek lampiran reimbursement saya?", ChatMessageOwnership.COLLEAGUE),
            row("old", "Pesan lama untuk pengujian.", ChatMessageOwnership.COLLEAGUE),
        )

    private fun row(id: String, text: String, own: ChatMessageOwnership) =
        ChatMessageRow(
            id,
            id,
            null,
            1_793_491_200_000,
            own,
            CollaborationMessageKind.TEXT,
            text,
            CollaborationMessageDelivery.SENT,
        )

    private fun screenshot(name: String) {
        Image.makeFromBitmap(compose.onNodeWithTag("frame").captureToImage().asSkiaBitmap()).use {
            image ->
            checkNotNull(image.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/$name.png").writeBytes(data.bytes)
            }
        }
    }
}
