package dev.fajar.starter.collaboration.presentation.thread.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.designsystem.components.AppIconButton
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Dismiss
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
internal fun ChatMessageBubble(
    row: ChatMessageRow,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    val own = row.ownership == ChatMessageOwnership.SELF
    Box(
        modifier.fillMaxWidth(),
        contentAlignment = if (own) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            Modifier.fillMaxWidth(0.9f)
                .widthIn(max = 540.dp)
                .background(
                    if (own) AppColors.tint else AppColors.surface,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (row.ownership == ChatMessageOwnership.UNKNOWN)
                AppText(
                    appString(AppString.ChatUnknownSender),
                    style = FluentTheme.typography.caption,
                    color = AppColors.muted,
                )
            val body =
                when (row.kind) {
                    CollaborationMessageKind.TEXT -> row.text.orEmpty()
                    CollaborationMessageKind.REDACTED -> appString(AppString.ChatDeleted)
                    CollaborationMessageKind.UNABLE_TO_DECRYPT ->
                        appString(AppString.ChatEncryptedUnavailable)
                    CollaborationMessageKind.UNSUPPORTED -> appString(AppString.ChatUnsupported)
                }
            SelectionContainer { AppText(body) }
            val local =
                Instant.fromEpochMilliseconds(row.timestamp)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
            val time =
                local.hour.toString().padStart(2, '0') +
                    ":" +
                    local.minute.toString().padStart(2, '0')
            val details = buildList {
                add(formatLocalDate(local.date.toString(), appLanguageTag()) + " · " + time)
                if (row.edited) add(appString(AppString.ChatEdited))
                if (own)
                    add(
                        appString(
                            when (row.delivery) {
                                CollaborationMessageDelivery.PENDING -> AppString.ChatQueued
                                CollaborationMessageDelivery.FAILED -> AppString.ChatSendFailed
                                CollaborationMessageDelivery.SENT -> AppString.ChatSent
                            }
                        )
                    )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    details.joinToString(" · "),
                    modifier = Modifier.weight(1f),
                    style = FluentTheme.typography.caption,
                    color = AppColors.muted,
                )
                if (onCancel != null)
                    AppIconButton(
                        Icons.Regular.Dismiss,
                        appString(AppString.ChatCancelSend),
                        onCancel,
                    )
            }
        }
    }
}
