package dev.fajar.starter.collaboration.presentation.replies.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.replies.*
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun SavedRepliesPage(
    state: SavedRepliesState,
    list: LazyListState,
    onEvent: (SavedRepliesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.replyId.isEmpty()) {
        AppLazyPage(
            modifier = modifier,
            state = list,
            maxWidth = 640.dp,
            spacing = 0.dp,
            contentPadding = PaddingValues(vertical = 8.dp),
            topBar = { SavedRepliesBar(state, onEvent) },
        ) {
            item("hint") {
                AppText(
                    appString(AppString.ChatSavedRepliesHint),
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = AppColors.muted,
                )
            }
            if (state.loading) item("loading") { AppLoading() }
            state.failure?.let { failure ->
                item("failure") { AppFeedback(failureText(failure), Modifier.padding(16.dp)) }
            }
            if (!state.loading && state.failure == null && state.snapshot?.items?.isEmpty() == true)
                item("empty") {
                    AppText(appString(AppString.ChatSavedRepliesEmpty), Modifier.padding(16.dp))
                }
            items(
                state.snapshot?.items.orEmpty(),
                key = { it.input.id },
                contentType = { "saved-reply" },
            ) { row ->
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 76.dp)
                        .clickable(role = Role.Button) {
                            onEvent(SavedRepliesEvent.ReplySelected(row.input.id))
                        }
                        .semantics(mergeDescendants = true) {}
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AppText(
                        row.conversationName ?: appString(AppString.ChatSavedReply),
                        style = FluentTheme.typography.bodyStrong,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AppText(
                        row.input.candidate.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AppText(
                        appString(
                            if (row.input.review == null) AppString.ChatReplyWaiting
                            else AppString.ChatReplyNeedsReview
                        ),
                        color = AppColors.muted,
                        style = FluentTheme.typography.caption,
                    )
                }
            }
        }
    } else {
        AppPage(
            modifier = modifier,
            maxWidth = 640.dp,
            contentPadding = PaddingValues(16.dp),
            spacing = 16.dp,
            topBar = { SavedRepliesBar(state, onEvent) },
        ) {
            if (state.loading || state.busy) AppLoading()
            state.failure?.let { AppFeedback(failureText(it)) }
            state.outcome?.let { AppFeedback(appString(discardOutcome(it))) }
            if (state.actionFailure != null)
                AppFeedback(appString(AppString.ChatReplyDiscardUnconfirmed))
            val selected = state.selected
            if (selected != null && state.failure == null) {
                AppText(
                    selected.conversationName ?: appString(AppString.ChatSavedReply),
                    style = FluentTheme.typography.subtitle,
                )
                AppText(appString(reviewHint(selected.input.review)), color = AppColors.muted)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppText(
                        appString(AppString.ChatReplyBody),
                        style = FluentTheme.typography.bodyStrong,
                    )
                    SelectionContainer { AppText(selected.input.candidate.text) }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selected.conversationName != null)
                        AppButton(
                            appString(AppString.ChatReplyOpenChat),
                            { onEvent(SavedRepliesEvent.OpenChatRequested) },
                            enabled = !state.busy,
                        )
                    AppTextButton(
                        appString(AppString.ChatReplyDiscard),
                        { onEvent(SavedRepliesEvent.DiscardRequested) },
                        enabled = !state.busy,
                    )
                }
            } else if (
                !state.loading && !state.busy && state.failure == null && state.outcome == null
            ) {
                AppText(appString(AppString.ChatReplyAbsent), color = AppColors.muted)
            }
        }
    }
    if (state.confirmation && state.selected != null && state.failure == null)
        AppDialog(
            appString(AppString.ChatReplyDiscardTitle),
            appString(AppString.Close),
            { onEvent(SavedRepliesEvent.DiscardDismissed) },
        ) {
            Column(
                Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppText(appString(AppString.ChatReplyDiscardHint))
                if (state.actionFailure != null)
                    AppFeedback(appString(AppString.ChatReplyDiscardUnconfirmed))
            }
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                AppButton(
                    appString(AppString.ChatReplyDiscard),
                    { onEvent(SavedRepliesEvent.DiscardConfirmed) },
                    Modifier.fillMaxWidth(),
                    loading = state.busy,
                )
            }
        }
}

@Composable
private fun SavedRepliesBar(state: SavedRepliesState, onEvent: (SavedRepliesEvent) -> Unit) {
    AppTopBar(
        title =
            appString(
                if (state.replyId.isEmpty()) AppString.ChatSavedReplies
                else AppString.ChatSavedReply
            ),
        navigation = {
            AppIconButton(
                Icons.Regular.ArrowLeft,
                appString(AppString.Back),
                { onEvent(SavedRepliesEvent.BackRequested) },
            )
        },
        actions = {
            AppIconButton(
                Icons.Regular.ArrowSync,
                appString(AppString.Refresh),
                { onEvent(SavedRepliesEvent.RefreshRequested) },
                enabled = !state.loading && !state.busy,
            )
        },
    )
}

private fun reviewHint(review: CollaborationNotificationReplyReview?) =
    when (review) {
        null -> AppString.ChatReplyWaitingHint
        CollaborationNotificationReplyReview.SessionChanged -> AppString.ChatReplySessionChanged
        CollaborationNotificationReplyReview.MessageChanged -> AppString.ChatReplyMessageChanged
        CollaborationNotificationReplyReview.ContextUnavailable ->
            AppString.ChatReplyContextUnavailable
    }

private fun discardOutcome(outcome: CollaborationReplyDiscard) =
    when (outcome) {
        CollaborationReplyDiscard.Discarded -> AppString.ChatReplyDiscarded
        CollaborationReplyDiscard.Absent -> AppString.ChatReplyAbsent
        CollaborationReplyDiscard.AlreadyProcessing -> AppString.ChatReplyAlreadyProcessing
        CollaborationReplyDiscard.MessageChanged -> AppString.ChatReplyMessageChanged
    }
