package dev.fajar.starter.collaboration.presentation.thread.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.collaboration.presentation.widgets.ChatForwardRecipientRow
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.*

@Composable
internal fun ChatForwardPage(
    state: ChatForwardState,
    onEvent: (ChatThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(AppColors.canvas).safeDrawingPadding().imePadding()) {
        AppTopBar(
            appString(AppString.ChatForwardTo),
            navigation = {
                AppIconButton(
                    Icons.Regular.ArrowLeft,
                    appString(AppString.Back),
                    { onEvent(ChatThreadEvent.ForwardDismissed) },
                )
            },
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppSearchField(
                state.query,
                { onEvent(ChatThreadEvent.ForwardSearchChanged(it)) },
                appString(AppString.ChatSearchPeople),
                appString(AppString.ClearSearch),
            )
            AppText(
                appString(AppString.ChatForwardTargetLimit, state.targetLimit.toString()),
                color = AppColors.muted,
                style = FluentTheme.typography.caption,
            )
        }
        val matching =
            state.targets.filter {
                it.peer.displayName.contains(state.query.trim(), ignoreCase = true)
            }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (state.loading && state.targets.isEmpty()) item("loading") { AppLoading() }
            items(matching, key = { it.conversation.id }, contentType = { "forward-target" }) {
                target ->
                val selected = target.conversation.id in state.selectedIds
                ChatForwardRecipientRow(
                    target.peer.displayName,
                    selected,
                    { onEvent(ChatThreadEvent.ForwardTargetSelected(target.conversation.id)) },
                    Modifier.testTag("chat-forward-target-${target.conversation.id}"),
                    enabled =
                        !state.busy &&
                            !state.locked &&
                            (selected || state.selectedIds.size < state.targetLimit),
                )
            }
            if (!state.loading && matching.isEmpty() && state.failure == null)
                item("empty") {
                    AppText(
                        appString(
                            if (state.query.isBlank()) AppString.ChatForwardEmpty
                            else AppString.ChatForwardNoMatches
                        ),
                        Modifier.padding(16.dp),
                        color = AppColors.muted,
                    )
                }
        }
        if (state.failure != null)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val message =
                    when {
                        state.targets.isEmpty() -> AppString.ChatForwardTargetsFailed
                        state.failure.code == "client.collaboration_forward_changed" ->
                            AppString.ChatForwardChanged
                        else -> AppString.ChatForwardFailed
                    }
                AppFeedback(appString(message))
                if (state.targets.isEmpty() && !state.locked)
                    AppTextButton(
                        appString(AppString.Retry),
                        { onEvent(ChatThreadEvent.ForwardTargetsRetryRequested) },
                    )
            }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppText(
                appString(AppString.ChatSelectedCount, state.messageCount.toString()),
                Modifier.weight(1f),
                color = AppColors.muted,
            )
            AppButton(
                appString(
                    if (state.locked && state.failure != null) AppString.Retry
                    else AppString.ChatForward
                ),
                { onEvent(ChatThreadEvent.ForwardConfirmed) },
                modifier = Modifier.testTag("chat-forward-confirm"),
                enabled = state.canConfirm,
            )
        }
    }
}
