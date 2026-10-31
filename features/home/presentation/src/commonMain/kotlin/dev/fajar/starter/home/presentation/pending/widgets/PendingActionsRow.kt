package dev.fajar.starter.home.presentation.pending.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.home.presentation.pending.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronRight

@Composable
fun PendingActionsRow(state: PendingActionsRowState, onEvent: (PendingActionsEvent) -> Unit) {
    val openLabel = appString(AppString.PendingOpen, appString(state.group.title))
    Row(
        Modifier.fillMaxWidth()
            .testTag("pending-${state.group.name}")
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button) {
                onEvent(PendingActionsEvent.OpenRequested(state.group))
            }
            .semantics { contentDescription = openLabel }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText(appString(state.group.title), style = FluentTheme.typography.bodyStrong)
            val summary = state.summary
            if (summary != null) {
                if (summary.total == 0 && !summary.moreDrafts)
                    AppText(appString(AppString.PendingNone), color = AppColors.muted)
                if (summary.drafts > 0 || summary.moreDrafts)
                    AppText(
                        appString(
                            if (summary.moreDrafts) AppString.PendingDraftsAtLeast
                            else AppString.PendingDrafts,
                            summary.drafts,
                        )
                    )
                if (summary.queued > 0) AppText(appString(AppString.PendingQueued, summary.queued))
                if (summary.unconfirmed > 0)
                    AppText(appString(AppString.PendingUnconfirmed, summary.unconfirmed))
                if (summary.needsReview > 0)
                    AppText(appString(AppString.PendingReview, summary.needsReview))
                if (summary.transfers > 0)
                    AppText(appString(AppString.PendingTransfers, summary.transfers))
                if (summary.moreDrafts)
                    AppText(appString(AppString.PendingMoreDrafts), color = AppColors.muted)
            } else if (state.loading) AppText(appString(AppString.Loading), color = AppColors.muted)
            state.failure?.let {
                AppText(
                    failureText(it),
                    color = AppColors.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        AppIcon(Icons.Regular.ChevronRight, tint = AppColors.muted)
    }
}
