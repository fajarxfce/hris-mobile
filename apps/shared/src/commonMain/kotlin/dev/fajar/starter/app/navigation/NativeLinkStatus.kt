package dev.fajar.starter.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*

/** Compact status above navigation; its actions never cover a page's back controls. */
@Composable
internal fun NativeLinkStatus(
    opening: Boolean,
    failure: Failure?,
    canChooseCompany: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onChooseCompany: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!opening && failure == null) return
    Column(modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (opening) {
            AppLoading(label = appString(AppString.ChatOpening), compact = true)
        } else {
            AppText(
                appString(
                    if (failure?.code == "mfa_required") AppString.NativeChatOpenVerification
                    else AppString.NativeChatOpenUnavailable
                ),
                color = AppColors.error,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!opening) {
                if (failure?.code == "mfa_required" && canChooseCompany)
                    AppTextButton(appString(AppString.ChooseCompany), onChooseCompany)
                AppTextButton(appString(AppString.Retry), onRetry)
            }
            AppTextButton(appString(if (opening) AppString.Cancel else AppString.Close), onDismiss)
        }
    }
}
