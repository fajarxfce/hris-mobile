package dev.fajar.starter.payroll.presentation.detail

import dev.fajar.starter.payroll.domain.entities.PayslipDocumentLanguage

sealed interface PayslipEvent {
    data object Entered : PayslipEvent

    data object Closed : PayslipEvent

    data object RefreshRequested : PayslipEvent

    data object ExportRequested : PayslipEvent

    data object ExportDismissed : PayslipEvent

    data class ExportLanguageSelected(val language: PayslipDocumentLanguage) : PayslipEvent

    data object BackRequested : PayslipEvent

    data object VerificationRequested : PayslipEvent
}
