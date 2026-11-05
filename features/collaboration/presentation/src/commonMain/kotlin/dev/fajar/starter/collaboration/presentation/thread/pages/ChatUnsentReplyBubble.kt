package dev.fajar.starter.collaboration.presentation.thread.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.replies.ChatUnsentReply
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
internal fun ChatUnsentReplyBubble(
    reply: ChatUnsentReply,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.widthIn(max = minOf(maxWidth * 0.86f, 480.dp))
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.tint)
                .clickable(enabled = enabled, role = Role.Button, onClick = onReview)
                .testTag("chat-unsent-${reply.id}")
                .semantics(mergeDescendants = true) {}
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppText(reply.text)
            AppText(
                appString(AppString.ChatUnsentReview),
                style = FluentTheme.typography.caption,
                color = AppColors.error,
            )
        }
    }
}
