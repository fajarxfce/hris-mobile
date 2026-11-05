package dev.fajar.starter.collaboration.presentation.thread.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.designsystem.components.AppIcon
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Checkmark
import io.github.composefluent.icons.regular.Clock
import io.github.composefluent.icons.regular.ErrorCircle
import kotlinx.datetime.TimeZone

@Composable
internal fun ChatMessageBubble(
    row: ChatMessageRow,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selecting: Boolean = false,
    enabled: Boolean = true,
    quoteContent: (@Composable () -> Unit)? = null,
    onSelect: () -> Unit,
) {
    val own = row.ownership == ChatMessageOwnership.SELF
    val selectLabel = appString(AppString.ChatSelectMessage)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("chat-message-${row.key}")
            .background(if (selected) AppColors.accent.copy(alpha = 0.13f) else Color.Transparent)
            .combinedClickable(
                enabled = enabled,
                onLongClickLabel = selectLabel,
                onLongClick = onSelect,
                onClick = { if (selecting) onSelect() },
            )
            .semantics(mergeDescendants = true) {
                this.selected = selected
                if (enabled)
                    customActions =
                        listOf(
                            CustomAccessibilityAction(selectLabel) {
                                onSelect()
                                true
                            }
                        )
            },
        contentAlignment = if (own) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        if (selected)
            AppIcon(
                Icons.Regular.Checkmark,
                null,
                Modifier.align(Alignment.CenterStart).size(24.dp),
            )
        Column(
            Modifier.padding(start = if (selecting && !own) 32.dp else 0.dp)
                .widthIn(max = minOf(maxWidth * 0.86f, 480.dp))
                .background(
                    if (own) AppColors.tint else AppColors.surface,
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (own) 16.dp else 4.dp,
                        bottomEnd = if (own) 4.dp else 16.dp,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (row.ownership == ChatMessageOwnership.UNKNOWN)
                AppText(
                    appString(AppString.ChatUnknownSender),
                    style = FluentTheme.typography.caption,
                    color = AppColors.muted,
                )
            if (row.kind == CollaborationMessageKind.TEXT) quoteContent?.invoke()
            val body =
                when (row.kind) {
                    CollaborationMessageKind.TEXT -> row.text.orEmpty()
                    CollaborationMessageKind.REDACTED -> appString(AppString.ChatDeleted)
                    CollaborationMessageKind.UNABLE_TO_DECRYPT ->
                        appString(AppString.ChatEncryptedUnavailable)
                    CollaborationMessageKind.UNSUPPORTED -> appString(AppString.ChatUnsupported)
                }
            AppText(body)
            val time = chatMessageTime(row.timestamp, TimeZone.currentSystemDefault())
            Row(
                Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (row.edited)
                    AppText(
                        appString(AppString.ChatEdited),
                        style = FluentTheme.typography.caption,
                        color = AppColors.muted,
                    )
                AppText(time, style = FluentTheme.typography.caption, color = AppColors.muted)
                if (own) {
                    val (icon, label) =
                        when (row.delivery) {
                            CollaborationMessageDelivery.PENDING ->
                                Icons.Regular.Clock to AppString.ChatQueued
                            CollaborationMessageDelivery.FAILED ->
                                Icons.Regular.ErrorCircle to AppString.ChatSendFailed
                            CollaborationMessageDelivery.SENT ->
                                Icons.Regular.Checkmark to AppString.ChatSent
                        }
                    AppIcon(
                        icon,
                        appString(label),
                        Modifier.size(16.dp),
                        tint =
                            if (row.delivery == CollaborationMessageDelivery.FAILED) AppColors.error
                            else AppColors.muted,
                    )
                }
            }
        }
    }
}
