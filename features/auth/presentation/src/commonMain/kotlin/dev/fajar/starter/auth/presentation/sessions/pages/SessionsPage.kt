package dev.fajar.starter.auth.presentation.sessions.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.auth.presentation.sessions.*
import dev.fajar.starter.auth.presentation.sessions.widgets.SessionCard
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft

@Composable
fun SessionsPage(state: SessionsState, onEvent: (SessionsEvent) -> Unit) {
    AppLazyPage {
        item(key = "heading") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIconButton(
                    Icons.Regular.ArrowLeft,
                    appString(AppString.Back),
                    { onEvent(SessionsEvent.BackRequested) },
                )
                AppHeading(appString(AppString.ActiveSessions), Modifier.weight(1f))
                AppButton(
                    appString(AppString.Refresh),
                    { onEvent(SessionsEvent.RefreshRequested) },
                    enabled = !state.working,
                    primary = false,
                )
            }
        }
        if (state.working) item(key = "loading") { AppLoading() }
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
