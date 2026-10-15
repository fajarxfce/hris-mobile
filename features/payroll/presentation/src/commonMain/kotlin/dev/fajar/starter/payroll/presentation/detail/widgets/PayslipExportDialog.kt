package dev.fajar.starter.payroll.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.payroll.domain.entities.PayslipDocumentLanguage
import dev.fajar.starter.payroll.presentation.detail.PayslipEvent
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowDownload

@Composable
fun PayslipExportDialog(revision: Long, onEvent: (PayslipEvent) -> Unit) {
    AppDialog(
        appString(AppString.PayslipSavePdf),
        appString(AppString.Close),
        { onEvent(PayslipEvent.ExportDismissed) },
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            AppText(appString(AppString.PayslipRevision, revision), Modifier.padding(bottom = 8.dp))
            AppText(
                appString(AppString.PayslipExternalCopy),
                Modifier.padding(bottom = 8.dp),
                color = AppColors.muted,
            )
            AppListRow(
                appString(AppString.Indonesian),
                { onEvent(PayslipEvent.ExportLanguageSelected(PayslipDocumentLanguage.ID)) },
                trailing = { AppIcon(Icons.Regular.ArrowDownload) },
            )
            AppListRow(
                appString(AppString.English),
                { onEvent(PayslipEvent.ExportLanguageSelected(PayslipDocumentLanguage.EN)) },
                trailing = { AppIcon(Icons.Regular.ArrowDownload) },
            )
        }
    }
}
