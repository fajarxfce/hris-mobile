package dev.fajar.starter.auth.presentation.sessions.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.auth.presentation.sessions.*
import dev.fajar.starter.auth.presentation.sessions.widgets.SessionCard
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun SessionsPage(state: SessionsState, onEvent: (SessionsEvent) -> Unit) {
    AppLazyPage(
        maxWidth = 720.dp,
        spacing = 16.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                appString(AppString.ActiveSessions),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(SessionsEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(SessionsEvent.RefreshRequested) },
                        enabled = !state.working,
                        loading = state.working && state.confirmingId == null,
                    )
                },
            )
        },
    ) {
        if (state.working && state.directory == null) item(key = "loading") { AppLoading() }
        if (state.failure != null) item(key = "failure") { AppFeedback(failureText(state.failure)) }
        if (state.directory?.items?.isEmpty() == true)
            item(key = "empty") { AppText(appString(AppString.NoActiveSessions)) }
        items(state.directory?.items.orEmpty(), key = { "session/${it.id}" }) { session ->
            SessionCard(
                session,
                session.id == state.directory?.currentId,
                state.confirmingId == session.id,
                state.working,
                onEvent,
            )
        }
        item(key = "logout") {
            AppButton(
                appString(AppString.SignOutThisDevice),
                { onEvent(SessionsEvent.SignOutRequested) },
                enabled = !state.working,
                primary = false,
            )
        }
    }
}
