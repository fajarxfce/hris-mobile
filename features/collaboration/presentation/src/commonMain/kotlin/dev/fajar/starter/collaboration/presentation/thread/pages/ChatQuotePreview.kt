package dev.fajar.starter.collaboration.presentation.thread.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.CollaborationQuotedMessage
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
internal fun ChatQuotePreview(
    quote: CollaborationQuotedMessage?,
    selfSender: String?,
    peerName: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .background(AppColors.accent.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(AppColors.accent))
        Column(
            Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (quote != null)
                AppText(
                    if (quote.sender == selfSender) appString(AppString.ChatYou)
                    else peerName ?: appString(AppString.ChatUnknownSender),
                    style = FluentTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = AppColors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            AppText(
                quote?.text ?: appString(AppString.ChatQuoteUnavailable),
                style = FluentTheme.typography.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
