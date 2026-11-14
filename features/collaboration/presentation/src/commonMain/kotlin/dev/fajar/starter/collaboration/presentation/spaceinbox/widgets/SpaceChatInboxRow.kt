package dev.fajar.starter.collaboration.presentation.spaceinbox.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.collaboration.presentation.widgets.ChatPersonRow
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Checkmark
import io.github.composefluent.icons.regular.Clock
import io.github.composefluent.icons.regular.ErrorCircle
import kotlinx.datetime.*

/** One admitted retained row. The list owns identity, labels, positioning and date updates. */
@Composable
fun SpaceChatInboxRow(
    entry: CollaborationSpaceInboxEntry,
    name: String,
    today: LocalDate,
    zone: TimeZone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val draft = entry.draft?.value
    val preview = entry.newest?.preview
    val timestamp = draft?.savedAt?.toEpochMilliseconds() ?: preview?.timestamp
    ChatPersonRow(
        name = name,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        supporting =
            if (draft == null && preview == null) null
            else {
                {
                    SpaceChatInboxPreview(
                        text =
                            if (draft != null)
                                "${appString(AppString.ChatDraftLabel)}: ${draft.text}"
                            else inboxPreviewText(requireNotNull(preview)),
                        draft = draft != null,
                        delivery = if (draft != null) null else preview?.delivery,
                    )
                }
            },
        trailing = timestamp?.let { at -> { SpaceChatInboxTime(at, today, zone) } },
    )
}

@Composable
private fun SpaceChatInboxPreview(
    text: String,
    draft: Boolean,
    delivery: CollaborationMessageDelivery?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (delivery != null) {
            val icon =
                when (delivery) {
                    CollaborationMessageDelivery.PENDING -> Icons.Regular.Clock
                    CollaborationMessageDelivery.FAILED -> Icons.Regular.ErrorCircle
                    CollaborationMessageDelivery.SENT -> Icons.Regular.Checkmark
                }
            val label =
                when (delivery) {
                    CollaborationMessageDelivery.PENDING -> AppString.ChatQueued
                    CollaborationMessageDelivery.FAILED -> AppString.ChatSendFailed
                    CollaborationMessageDelivery.SENT -> AppString.ChatSent
                }
            AppIcon(
                icon,
                appString(label),
                Modifier.size(16.dp),
                tint =
                    if (delivery == CollaborationMessageDelivery.FAILED) AppColors.error
                    else AppColors.muted,
            )
        }
        AppText(
            text,
            Modifier.weight(1f),
            style = FluentTheme.typography.body,
            color = if (draft) AppColors.accent else AppColors.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SpaceChatInboxTime(timestamp: Long, today: LocalDate, zone: TimeZone) {
    val date = chatMessageDate(timestamp, zone)
    val label =
        if (date == today) chatMessageTime(timestamp, zone)
        else chatDayResource(date, today)?.let { appString(it) } ?: chatCalendarDate(date)
    AppText(
        label,
        Modifier.widthIn(max = 140.dp),
        style = FluentTheme.typography.caption,
        color = AppColors.muted,
    )
}

@Composable
private fun inboxPreviewText(preview: CollaborationSpaceInboxPreview): String =
    when (preview.kind) {
        CollaborationSpaceInboxContent.TEXT -> requireNotNull(preview.text)
        CollaborationSpaceInboxContent.IMAGE -> appString(AppString.ChatPreviewPhoto)
        CollaborationSpaceInboxContent.VIDEO -> appString(AppString.ChatPreviewVideo)
        CollaborationSpaceInboxContent.AUDIO -> appString(AppString.ChatPreviewAudio)
        CollaborationSpaceInboxContent.FILE -> appString(AppString.ChatPreviewFile)
        CollaborationSpaceInboxContent.STICKER -> appString(AppString.ChatPreviewSticker)
        CollaborationSpaceInboxContent.UNAVAILABLE -> appString(AppString.ChatPreviewUnavailable)
        CollaborationSpaceInboxContent.DELETED -> appString(AppString.ChatDeleted)
    }
