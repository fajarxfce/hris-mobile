package dev.fajar.starter.collaboration.presentation.participants.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.participants.*
import dev.fajar.starter.collaboration.presentation.widgets.ChatPersonRow
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft

@Composable
fun ChatParticipantsPage(
    state: ChatParticipantsState,
    listState: LazyListState,
    onEvent: (ChatParticipantsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    AppLazyPage(
        modifier = modifier.imePadding(),
        state = listState,
        spacing = 0.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(vertical = 8.dp),
        topBar = {
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppIconButton(
                    Icons.Regular.ArrowLeft,
                    appString(AppString.Back),
                    { onEvent(ChatParticipantsEvent.BackRequested) },
                )
                AppSearchField(
                    state.query,
                    { onEvent(ChatParticipantsEvent.QueryChanged(it)) },
                    appString(AppString.ChatSearchPeople),
                    appString(AppString.ClearSearch),
                    Modifier.weight(1f).padding(end = 8.dp),
                    enabled = state.selected == null,
                    autoFocus = true,
                )
            }
        },
    ) {
        val selected = state.selected
        if (selected != null) {
            item("selection") {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AppText(selected.displayName, style = FluentTheme.typography.subtitle)
                    if (state.opening) AppLoading(appString(AppString.ChatOpening), compact = true)
                    state.openFailure?.let { AppFeedback(failureText(it)) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!state.opening)
                            AppButton(
                                appString(AppString.Retry),
                                { onEvent(ChatParticipantsEvent.RetryOpenRequested) },
                                enabled = state.localFailure == null,
                            )
                        AppTextButton(
                            appString(AppString.Cancel),
                            { onEvent(ChatParticipantsEvent.CancelSelectionRequested) },
                        )
                    }
                }
            }
        } else {
            val failure = state.localFailure ?: state.failure
            if (failure != null)
                item("failure") {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppFeedback(failureText(failure))
                        AppTextButton(
                            appString(AppString.Retry),
                            { onEvent(ChatParticipantsEvent.RetryRequested) },
                            enabled = !state.loading,
                        )
                    }
                }
            if (state.loading) item("loading") { AppLoading() }
            if (!state.loading && failure == null && state.page?.items?.isEmpty() == true)
                item("empty") {
                    AppText(
                        appString(AppString.ChatParticipantsEmpty),
                        Modifier.padding(24.dp),
                        color = AppColors.muted,
                    )
                }
            if (state.localFailure == null)
                items(
                    state.page?.items.orEmpty(),
                    key = { it.accountId },
                    contentType = { "person" },
                ) { person ->
                    ChatPersonRow(
                        person.displayName,
                        {
                            focus.clearFocus()
                            onEvent(ChatParticipantsEvent.ParticipantSelected(person.accountId))
                        },
                        enabled = !state.loading,
                    )
                }
            if (state.previous.isNotEmpty() || state.page?.nextCursor != null)
                item("pagination") {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (state.previous.isNotEmpty())
                            AppTextButton(
                                appString(AppString.ChatPeoplePrevious),
                                { onEvent(ChatParticipantsEvent.PreviousRequested) },
                                enabled = !state.loading,
                            )
                        if (state.page?.nextCursor != null)
                            AppTextButton(
                                appString(AppString.ChatPeopleNext),
                                { onEvent(ChatParticipantsEvent.NextRequested) },
                                enabled = !state.loading,
                            )
                    }
                }
        }
    }
}
