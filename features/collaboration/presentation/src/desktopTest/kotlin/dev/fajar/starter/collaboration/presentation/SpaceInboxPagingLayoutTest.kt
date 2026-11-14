package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.paging.*
import androidx.paging.compose.collectAsLazyPagingItems
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceInboxEntry
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.spaceinbox.*
import dev.fajar.starter.collaboration.presentation.spaceinbox.pages.SpaceChatInboxPage
import dev.fajar.starter.common.result.*
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test

class SpaceInboxPagingLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate(2026, 11, 14)
    private val version = CollaborationSpaceInboxVersion(chatId(901), 1, 1)
    private val rows =
        (1..5).map { collaborationSpaceInboxEntry(chatId(it), 1000L - it, null, null) }
    private val names =
        rows.mapIndexed { index, row -> row.conversationId to "Colleague $index" }.toMap()

    @Test
    fun storedRowsRemainVisibleWhileAReplacementGenerationIsStillReading() {
        val publication = MutableStateFlow(SpaceChatInboxPublication(1, AppResult.Success(version)))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val queries = CopyOnWriteArrayList<CollaborationSpaceInboxWindowAnchor>()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val pages = remember {
                Pager(PagingConfig(50, initialLoadSize = 50, enablePlaceholders = false)) {
                        SpaceChatInboxPagingSource(scope, publication) { anchor, _ ->
                            queries += anchor
                            val current = (publication.value.result as AppResult.Success).value
                            if (current != version) {
                                entered.complete(Unit)
                                release.await()
                            }
                            AppResult.Success(
                                CollaborationSpaceInboxWindow.Items(current, rows, null, null)
                            )
                        }
                    }
                    .flow
                    .cachedIn(scope)
            }
            val items = pages.collectAsLazyPagingItems()
            AppTheme(reduceMotion = true) {
                Box(Modifier.size(375.dp, 780.dp)) {
                    SpaceChatInboxPage(
                        SpaceChatInboxState(visible = true, names = names),
                        items,
                        rememberLazyListState(),
                        today,
                        TimeZone.UTC,
                        {},
                    )
                }
            }
        }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithText("Colleague 0", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Colleague 0", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            publication.value =
                SpaceChatInboxPublication(2, AppResult.Success(version.copy(revision = 2)))
        }
        compose.waitUntil(5_000) { entered.isCompleted }
        compose.onNodeWithText("Colleague 0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Colleague 4", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(2, queries.size)
        compose.runOnIdle { release.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithText("Colleague 0", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun exhaustedLocalPagesStayExhaustedWhenTheListCompositionReopens() {
        val publication = MutableStateFlow(SpaceChatInboxPublication(1, AppResult.Success(version)))
        val queries = CopyOnWriteArrayList<CollaborationSpaceInboxWindowAnchor>()
        var shown by mutableStateOf(true)
        compose.setContent {
            val scope = rememberCoroutineScope()
            val pages = remember {
                Pager(
                        PagingConfig(
                            3,
                            initialLoadSize = 3,
                            prefetchDistance = 1,
                            maxSize = 15,
                            enablePlaceholders = false,
                        )
                    ) {
                        SpaceChatInboxPagingSource(scope, publication) { anchor, _ ->
                            queries += anchor
                            AppResult.Success(
                                if (anchor is CollaborationSpaceInboxWindowAnchor.Older)
                                    CollaborationSpaceInboxWindow.Items(
                                        version,
                                        rows.drop(3),
                                        "previous",
                                        null,
                                    )
                                else
                                    CollaborationSpaceInboxWindow.Items(
                                        version,
                                        rows.take(3),
                                        null,
                                        "remaining",
                                    )
                            )
                        }
                    }
                    .flow
                    .cachedIn(scope)
            }
            if (shown) {
                val items = pages.collectAsLazyPagingItems()
                AppTheme(reduceMotion = true) {
                    Box(Modifier.size(375.dp, 780.dp)) {
                        SpaceChatInboxPage(
                            SpaceChatInboxState(visible = true, names = names),
                            items,
                            rememberLazyListState(),
                            today,
                            TimeZone.UTC,
                            {},
                            Modifier.testTag("inbox"),
                        )
                    }
                }
            }
        }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithText("Colleague 4", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertEquals(2, queries.size)
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        compose.runOnIdle { shown = true }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithText("Colleague 4", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Colleague 4", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(2, queries.size)
    }

    @Test
    fun aConfirmedRestrictionImmediatelyHidesAlreadyPresentedPrivateRows() {
        val publication = MutableStateFlow(SpaceChatInboxPublication(1, AppResult.Success(version)))
        var state by mutableStateOf(SpaceChatInboxState(visible = true, names = names))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val pages = remember {
                Pager(PagingConfig(50, enablePlaceholders = false)) {
                        SpaceChatInboxPagingSource(scope, publication) { _, _ ->
                            AppResult.Success(
                                CollaborationSpaceInboxWindow.Items(version, rows, null, null)
                            )
                        }
                    }
                    .flow
                    .cachedIn(scope)
            }
            val items = pages.collectAsLazyPagingItems()
            AppTheme(reduceMotion = true) {
                Box(Modifier.size(375.dp, 780.dp)) {
                    SpaceChatInboxPage(
                        state,
                        items,
                        rememberLazyListState(),
                        today,
                        TimeZone.UTC,
                        {},
                    )
                }
            }
        }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithText("Colleague 0", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.runOnIdle {
            state =
                state.copy(
                    restriction = Failure(FailureKind.AccessDenied, "Denied"),
                    names = emptyMap(),
                )
        }
        compose.onNodeWithText("Colleague 0", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Colleague 4", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun insertingANewerConversationKeepsTheVisibleConversationAtItsScrollOffset() {
        val publication = MutableStateFlow(SpaceChatInboxPublication(1, AppResult.Success(version)))
        val original =
            (1..100).map { collaborationSpaceInboxEntry(chatId(it), 1000L - it, null, null) }
        val replacement =
            listOf(collaborationSpaceInboxEntry(chatId(101), 1001, null, null)) + original
        val queries = CopyOnWriteArrayList<CollaborationSpaceInboxWindowAnchor>()
        lateinit var list: LazyListState
        lateinit var owner: CoroutineScope
        compose.setContent {
            val scope = rememberCoroutineScope()
            val scrolling = rememberLazyListState()
            SideEffect {
                list = scrolling
                owner = scope
            }
            val pages = remember {
                Pager(
                        PagingConfig(
                            20,
                            initialLoadSize = 20,
                            prefetchDistance = 2,
                            enablePlaceholders = false,
                        )
                    ) {
                        SpaceChatInboxPagingSource(scope, publication) { anchor, count ->
                            queries += anchor
                            val current = (publication.value.result as AppResult.Success).value
                            val available = if (current == version) original else replacement
                            val start =
                                when (anchor) {
                                    CollaborationSpaceInboxWindowAnchor.Latest -> 0
                                    is CollaborationSpaceInboxWindowAnchor.Conversation ->
                                        (available.indexOfFirst { it.conversationId == anchor.id } -
                                                count / 2)
                                            .coerceAtLeast(0)
                                    is CollaborationSpaceInboxWindowAnchor.Older ->
                                        anchor.cursor.toInt()
                                    is CollaborationSpaceInboxWindowAnchor.Newer ->
                                        (anchor.cursor.toInt() - count).coerceAtLeast(0)
                                }
                            val selected = available.drop(start).take(count)
                            AppResult.Success(
                                CollaborationSpaceInboxWindow.Items(
                                    current,
                                    selected,
                                    start.takeIf { it > 0 }?.toString(),
                                    (start + selected.size)
                                        .takeIf { it < available.size }
                                        ?.toString(),
                                )
                            )
                        }
                    }
                    .flow
                    .cachedIn(scope)
            }
            val items = pages.collectAsLazyPagingItems()
            AppTheme(reduceMotion = true) {
                Box(Modifier.size(375.dp, 200.dp)) {
                    SpaceChatInboxPage(
                        SpaceChatInboxState(
                            visible = true,
                            names = replacement.associate { it.conversationId to it.conversationId },
                        ),
                        items,
                        scrolling,
                        today,
                        TimeZone.UTC,
                        {},
                        Modifier.testTag("inbox"),
                    )
                }
            }
        }
        compose.waitUntil(5_000) { queries.isNotEmpty() && list.layoutInfo.totalItemsCount >= 20 }
        val scrolled = CompletableDeferred<Unit>()
        compose.runOnIdle {
            owner.launch {
                list.scrollToItem(12, 9)
                scrolled.complete(Unit)
            }
        }
        compose.waitUntil(5_000) { scrolled.isCompleted }
        compose.waitForIdle()
        val first = list.layoutInfo.visibleItemsInfo.first()
        val key = first.key
        val offset = first.offset
        val previousIndex = first.index
        compose.runOnIdle {
            publication.value =
                SpaceChatInboxPublication(2, AppResult.Success(version.copy(revision = 2)))
        }
        compose.waitUntil(5_000) {
            queries.any { it is CollaborationSpaceInboxWindowAnchor.Conversation } &&
                list.layoutInfo.visibleItemsInfo.firstOrNull()?.let {
                    it.key == key && it.index != previousIndex
                } == true
        }
        compose.waitForIdle()
        val retained = list.layoutInfo.visibleItemsInfo.first()
        assertEquals(key, retained.key)
        assertEquals(offset, retained.offset)
        assertEquals(1, queries.count { it is CollaborationSpaceInboxWindowAnchor.Conversation })
    }

    @Test
    fun failedContinuationKeepsRowsAndOffersRetryOnlyWhileThatReadIsFailed() {
        val publication = MutableStateFlow(SpaceChatInboxPublication(1, AppResult.Success(version)))
        val queries = CopyOnWriteArrayList<CollaborationSpaceInboxWindowAnchor>()
        val recovered = AtomicBoolean(false)
        compose.setContent {
            val scope = rememberCoroutineScope()
            val pages = remember {
                Pager(
                        PagingConfig(
                            3,
                            initialLoadSize = 3,
                            prefetchDistance = 1,
                            enablePlaceholders = false,
                        )
                    ) {
                        SpaceChatInboxPagingSource(scope, publication) { anchor, _ ->
                            queries += anchor
                            if (
                                anchor is CollaborationSpaceInboxWindowAnchor.Older &&
                                    !recovered.get()
                            ) {
                                AppResult.Failed(
                                    Failure(FailureKind.Storage, "client.storage_failed")
                                )
                            } else
                                AppResult.Success(
                                    if (anchor is CollaborationSpaceInboxWindowAnchor.Older)
                                        CollaborationSpaceInboxWindow.Items(
                                            version,
                                            rows.drop(3),
                                            "previous",
                                            null,
                                        )
                                    else
                                        CollaborationSpaceInboxWindow.Items(
                                            version,
                                            rows.take(3),
                                            null,
                                            "remaining",
                                        )
                                )
                        }
                    }
                    .flow
                    .cachedIn(scope)
            }
            val items = pages.collectAsLazyPagingItems()
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                AppTheme(reduceMotion = true) {
                    Box(Modifier.size(375.dp, 780.dp)) {
                        SpaceChatInboxPage(
                            SpaceChatInboxState(visible = true, names = names),
                            items,
                            rememberLazyListState(),
                            today,
                            TimeZone.UTC,
                            {},
                        )
                    }
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Colleague 0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Colleague 2", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Colleague 4", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(2, queries.size)
        recovered.set(true)
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithText("Colleague 4", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Colleague 0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Colleague 4", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        assertEquals(3, queries.size)
    }
}
