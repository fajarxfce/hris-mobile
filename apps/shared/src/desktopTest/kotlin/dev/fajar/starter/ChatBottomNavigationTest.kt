package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.inbox.ChatInboxState
import dev.fajar.starter.collaboration.presentation.inbox.pages.ChatInboxPage
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.home.presentation.home.*
import dev.fajar.starter.home.presentation.home.pages.HomePage
import dev.fajar.starter.identity.domain.entities.CompanyMembership
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.time.Instant
import org.junit.Rule
import org.junit.Test

class ChatBottomNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun chatIsASelectedBottomTabWithoutAnExtraBackButton() {
        val at = Instant.parse("2026-11-03T00:00:00Z")
        val workspace =
            Workspace(
                "workspace",
                "session",
                "https://example.test/",
                CompanyMembership("company", "Example", "EX", "Asia/Jakarta"),
                setOf("collaboration.chat"),
                WorkspaceAdmission("account", "family", at, at),
            )
        var state by mutableStateOf(HomeState(workspace, tab = HomeTab.Chat))
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Indonesian) {
                AppTheme {
                    Box(Modifier.size(375.dp, 720.dp)) {
                        HomePage(
                            state,
                            {
                                when (it) {
                                    is HomeEvent.TabSelected -> state = state.copy(tab = it.tab)
                                    is HomeEvent.ChatSectionSelected ->
                                        state = state.copy(chatSection = it.section)
                                    else -> Unit
                                }
                            },
                            attendance = {},
                            inbox = { AppText("Pemberitahuan perusahaan") },
                            employees = { header ->
                                Column {
                                    header()
                                    AppText("Direktori karyawan")
                                }
                            },
                            inboxBadge = {},
                            chat = { header ->
                                ChatInboxPage(
                                    ChatInboxState(loading = false),
                                    rememberLazyListState(),
                                    {},
                                    onBack = null,
                                    topBar = header,
                                )
                            },
                        )
                    }
                }
            }
        }
        compose
            .onNode(hasText("Chat") and isSelectable())
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Kembali").assertDoesNotExist()
        compose.onNodeWithContentDescription("Chat baru").assertIsDisplayed()
        compose.onNode(hasText("Karyawan") and isSelectable()).assertDoesNotExist()
        compose.onNode(hasText("Kontak") and isSelectable()).performClick().assertIsSelected()
        compose.onNodeWithText("Direktori karyawan").assertIsDisplayed()
        compose.onNode(hasText("Chat") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Obrolan") and isSelectable()).performClick().assertIsSelected()
        compose.onNode(hasText("Chat") and isSelectable()).performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Chat baru").assertIsDisplayed()
    }
}
