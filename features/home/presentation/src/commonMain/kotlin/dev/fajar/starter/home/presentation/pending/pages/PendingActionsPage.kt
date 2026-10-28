package dev.fajar.starter.home.presentation.pending.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.home.presentation.pending.*
import dev.fajar.starter.home.presentation.pending.widgets.PendingActionsRow
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun PendingActionsPage(state: PendingActionsState, onEvent: (PendingActionsEvent) -> Unit) {
    AppLazyPage(
        maxWidth = 640.dp,
        spacing = 0.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                title = appString(AppString.PendingActions),
                subtitle = state.companyName,
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(PendingActionsEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.PendingReload),
                        { onEvent(PendingActionsEvent.ReloadRequested) },
                    )
                },
            )
        },
    ) {
        item(key = "summary") {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText(appString(AppString.PendingSummary), color = AppColors.muted)
                if (state.needsVerification)
                    AppTextButton(
                        appString(AppString.VerifyAccount),
                        { onEvent(PendingActionsEvent.VerificationRequested) },
                    )
            }
        }
        items(state.rows, key = { it.group.name }) { row -> PendingActionsRow(row, onEvent) }
    }
}
