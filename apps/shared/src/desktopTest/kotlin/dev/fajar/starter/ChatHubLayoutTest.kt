@file:OptIn(io.github.composefluent.ExperimentalFluentApi::class)

package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.ChatInboxState
import dev.fajar.starter.collaboration.presentation.inbox.pages.ChatInboxPage
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.home.presentation.home.*
import dev.fajar.starter.home.presentation.home.pages.HomePage
import dev.fajar.starter.identity.domain.entities.CompanyMembership
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.people.presentation.directory.*
import dev.fajar.starter.people.presentation.directory.pages.EmployeesPage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.workspace.domain.entities.*
import io.github.composefluent.*
import java.io.File
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.datetime.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class ChatHubLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactChatAndContactsShareOneNavigationDestination() = exercise(false, false)

    @Test fun largeDarkTextKeepsTabsAndSearchReachable() = exercise(true, false)

    @Test fun landscapeKeepsHeadersOutsideBothScrollingLists() = exercise(false, true)

    @Test
    fun gesturesSwitchTabsAndHideTheFloatingActionWithoutMovingHeaders() {
        val events = mutableListOf<HomeEvent>()
        renderHub(false, false, events, reduceMotion = false)
        compose.onNodeWithTag("hub-frame").performTouchInput {
            swipe(Offset(width * .85f, height * .35f), Offset(width * .15f, height * .35f), 350)
        }
        compose.onNode(hasText("Kontak") and isSelectable()).assertIsSelected()
        compose.onNodeWithContentDescription("Filter departemen").assertIsDisplayed()
        val tabs =
            compose.onNode(hasText("Kontak") and isSelectable()).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Chat baru").assertIsDisplayed()
        compose.onNodeWithTag("hub-frame").performTouchInput {
            swipe(Offset(width * .5f, height * .75f), Offset(width * .5f, height * .3f), 450)
        }
        compose.onNodeWithContentDescription("Chat baru").assertDoesNotExist()
        assertEquals(
            tabs,
            compose.onNode(hasText("Kontak") and isSelectable()).fetchSemanticsNode().boundsInRoot,
        )
        capture("chat-hub-floating-action-hidden")
        compose.onNodeWithTag("hub-frame").performTouchInput {
            swipe(Offset(width * .5f, height * .35f), Offset(width * .5f, height * .75f), 450)
        }
        compose.onNodeWithContentDescription("Chat baru").assertIsDisplayed()
        compose.onNodeWithTag("hub-frame").performTouchInput {
            swipe(Offset(width * .15f, height * .35f), Offset(width * .85f, height * .35f), 350)
        }
        compose.onNode(hasText("Obrolan") and isSelectable()).assertIsSelected()
        compose.onNodeWithText("Nadia Putri").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(
                listOf(ChatSection.Contacts, ChatSection.Conversations),
                events.filterIsInstance<HomeEvent.ChatSectionSelected>().map { it.section },
            )
        }
    }

    private fun exercise(dark: Boolean, landscape: Boolean) {
        val events = mutableListOf<HomeEvent>()
        renderHub(dark, landscape, events)
        compose
            .onNode(hasText("Chat") and isSelectable())
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
        compose.onNode(hasText("Obrolan") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Karyawan") and isSelectable()).assertDoesNotExist()
        compose.onAllNodesWithText("Chat").assertCountEquals(1)
        compose.onNodeWithText("Nadia Putri").assertIsDisplayed()
        val suffix = if (landscape) "landscape" else if (dark) "dark-large-text" else "compact"
        capture("chat-hub-conversations-$suffix")
        compose
            .onNode(hasText("Kontak") and isSelectable())
            .assertHeightIsAtLeast(48.dp)
            .performClick()
            .assertIsSelected()
        compose
            .onNodeWithContentDescription("Cari karyawan")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Kembali").assertDoesNotExist()
        compose.onNodeWithContentDescription("Refresh").assertDoesNotExist()
        val search =
            compose.onNodeWithContentDescription("Cari karyawan").fetchSemanticsNode().boundsInRoot
        val filter =
            compose
                .onNodeWithContentDescription("Filter departemen")
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(filter.left >= search.right)
        assertEquals(search.center.y, filter.center.y, .5f)
        compose.onNodeWithText("Semua departemen").assertDoesNotExist()
        capture("chat-hub-contacts-$suffix")
        val headerBounds =
            compose.onNode(hasText("Kontak") and isSelectable()).fetchSemanticsNode().boundsInRoot
        compose
            .onNode(
                hasScrollToIndexAction() and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            )
            .performScrollToNode(hasText("Rekan 20"))
        compose.onNodeWithText("Rekan 20").assertIsDisplayed()
        assertEquals(
            headerBounds,
            compose.onNode(hasText("Kontak") and isSelectable()).fetchSemanticsNode().boundsInRoot,
        )
        compose.onNode(hasText("Obrolan") and isSelectable()).performClick()
        compose.onNodeWithText("Nadia Putri").assertIsDisplayed()
        compose.onNode(hasText("Kontak") and isSelectable()).performClick()
        compose.onNodeWithText("Rekan 20").assertIsDisplayed()
        compose
            .onNodeWithContentDescription("Chat baru")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle { assertTrue(HomeEvent.NewChatRequested in events) }
    }

    private fun renderHub(
        dark: Boolean,
        landscape: Boolean,
        events: MutableList<HomeEvent>,
        reduceMotion: Boolean = true,
    ) {
        val directoryEvents = mutableListOf<EmployeesEvent>()
        var state by mutableStateOf(HomeState(hubWorkspace(), tab = HomeTab.Chat))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (dark) 2f else 1f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme(reduceMotion = reduceMotion) {
                    FluentTheme(
                        colors = Colors(generateShades(Color(0xFF0078D4)), darkMode = dark)
                    ) {
                        Box(
                            Modifier.size(
                                    if (landscape) 760.dp else 375.dp,
                                    if (landscape) 375.dp else 780.dp,
                                )
                                .testTag("hub-frame")
                        ) {
                            HomePage(
                                state,
                                { event ->
                                    events += event
                                    state =
                                        when (event) {
                                            is HomeEvent.TabSelected -> state.copy(tab = event.tab)
                                            is HomeEvent.ChatSectionSelected ->
                                                state.copy(chatSection = event.section)
                                            else -> state
                                        }
                                },
                                attendance = {},
                                inbox = { AppText("Pemberitahuan") },
                                employees = { _, bottomPadding ->
                                    EmployeesPage(
                                        hubContacts(),
                                        rememberLazyListState(),
                                        false,
                                        directoryEvents::add,
                                        topBar = {},
                                        bottomPadding = bottomPadding,
                                    )
                                },
                                inboxBadge = {},
                                chat = { _, bottomPadding ->
                                    ChatInboxPage(
                                        hubInbox(),
                                        rememberLazyListState(),
                                        {},
                                        onBack = null,
                                        zone = TimeZone.UTC,
                                        today = LocalDate(2026, 11, 5),
                                        topBar = {},
                                        bottomPadding = bottomPadding,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.onNodeWithTag("hub-frame").performMouseInput { moveTo(Offset.Zero) }
        compose.waitForIdle()
        Image.makeFromBitmap(compose.onNodeWithTag("hub-frame").captureToImage().asSkiaBitmap())
            .use { image ->
                checkNotNull(image.encodeToData()).use { data ->
                    File("build/reports/screenshots").mkdirs()
                    File("build/reports/screenshots/$name.png").writeBytes(data.bytes)
                }
            }
    }
}

private fun hubWorkspace(): Workspace {
    val at = Instant.parse("2026-11-05T00:00:00Z")
    return Workspace(
        "workspace",
        "session",
        "https://example.test/",
        CompanyMembership("company", "Contoh", "EX", "UTC"),
        setOf("collaboration.chat"),
        WorkspaceAdmission("self", "family", at, at),
    )
}

private fun hubContacts() =
    EmployeesState(
        page =
            EmployeeDirectoryContent(
                (1..20).map {
                    EmployeeRowState("$it", "Rekan $it", "Software Engineer", "Engineering", "R")
                },
                null,
            )
    )

private fun hubInbox(): ChatInboxState {
    val items =
        listOf("Nadia Putri", "Bima Santoso", "Dimas Saputra").mapIndexed { i, name ->
            CollaborationDirectSummary(
                CollaborationDirectConversation(
                    "chat-$i",
                    "company",
                    "self",
                    "peer-$i",
                    "self",
                    0,
                    0,
                ),
                CollaborationParticipant("peer-$i", name),
            )
        }
    val previews =
        items.associate { item ->
            item.conversation.id to
                CollaborationInboxPreview(
                    CollaborationHistoryMessage(
                        "@peer:example.test",
                        null,
                        "\$message",
                        Instant.parse("2026-11-05T09:15:00Z").toEpochMilliseconds(),
                        "a".repeat(64),
                        "Berkasnya sudah diterima, terima kasih.",
                        listOf("\$message"),
                        false,
                    ),
                    null,
                )
        }
    return ChatInboxState(
        page = CollaborationLocalList(null, items, null),
        previews = previews,
        loading = false,
    )
}
