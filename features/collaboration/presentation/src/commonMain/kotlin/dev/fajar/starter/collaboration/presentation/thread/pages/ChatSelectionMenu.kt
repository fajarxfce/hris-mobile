package dev.fajar.starter.collaboration.presentation.thread.pages

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageDelivery
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.*

@Composable
internal fun ChatSelectionMenu(state: ChatThreadState, onEvent: (ChatThreadEvent) -> Unit) {
    val offset = with(LocalDensity.current) { 48.dp.roundToPx() }
    Box {
        AppIconButton(
            Icons.Regular.MoreHorizontal,
            appString(AppString.ChatMoreActions),
            { onEvent(ChatThreadEvent.SelectionMenuOpened) },
        )
        if (state.selectionMenuOpen)
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, offset),
                onDismissRequest = { onEvent(ChatThreadEvent.SelectionMenuDismissed) },
                properties = PopupProperties(focusable = true),
            ) {
                AppCard(Modifier.widthIn(min = 220.dp, max = 300.dp), padding = 4.dp) {
                    val retry =
                        state.selectedMessages.singleOrNull()?.takeIf {
                            it.composerSubmission != null &&
                                it.delivery == CollaborationMessageDelivery.FAILED
                        }
                    if (retry != null)
                        AppListRow(
                            appString(AppString.Retry),
                            {
                                onEvent(ChatThreadEvent.SelectionMenuDismissed)
                                onEvent(
                                    ChatThreadEvent.MessageRetryRequested(
                                        requireNotNull(retry.composerSubmission).id
                                    )
                                )
                            },
                            leading = { AppIcon(Icons.Regular.ArrowClockwise) },
                        )
                    if (state.canEditSelection)
                        AppListRow(
                            appString(AppString.ChatEditMessage),
                            {
                                onEvent(ChatThreadEvent.SelectionMenuDismissed)
                                onEvent(ChatThreadEvent.EditSelected)
                            },
                            leading = { AppIcon(Icons.Regular.Edit) },
                        )
                    if (state.canCopyFailedEdits)
                        AppListRow(
                            appString(AppString.ChatCopyFailedEdits),
                            {
                                onEvent(ChatThreadEvent.SelectionMenuDismissed)
                                onEvent(ChatThreadEvent.CopyFailedEdits)
                            },
                            leading = { AppIcon(Icons.Regular.Copy) },
                        )
                    if (state.selectedMessages.singleOrNull()?.failedEditIds?.isNotEmpty() == true)
                        AppListRow(
                            appString(AppString.ChatDiscardEdit),
                            {
                                onEvent(ChatThreadEvent.SelectionMenuDismissed)
                                onEvent(ChatThreadEvent.FailedEditsDismissRequested)
                            },
                            enabled = state.canDismissFailedEdits,
                            leading = { AppIcon(Icons.Regular.Dismiss) },
                        )
                    AppListRow(
                        appString(AppString.ChatCopyMessages),
                        {
                            onEvent(ChatThreadEvent.SelectionMenuDismissed)
                            onEvent(ChatThreadEvent.CopySelected)
                        },
                        enabled = state.canCopySelection,
                        leading = { AppIcon(Icons.Regular.Copy) },
                    )
                    val queued =
                        state.selectedMessages.singleOrNull()?.takeIf {
                            it.canCancel && it.localId != null
                        }
                    AppListRow(
                        appString(
                            if (queued == null) AppString.ChatDeleteMessages
                            else AppString.ChatCancelSend
                        ),
                        {
                            onEvent(ChatThreadEvent.SelectionMenuDismissed)
                            onEvent(
                                if (queued == null) ChatThreadEvent.DeleteSelected
                                else ChatThreadEvent.CancelRequested(requireNotNull(queued.localId))
                            )
                        },
                        enabled = queued != null || state.canDeleteSelection,
                        leading = {
                            AppIcon(
                                if (queued == null) Icons.Regular.Delete else Icons.Regular.Dismiss
                            )
                        },
                    )
                }
            }
    }
}
