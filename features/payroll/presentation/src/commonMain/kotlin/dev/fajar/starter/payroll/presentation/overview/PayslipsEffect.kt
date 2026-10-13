package dev.fajar.starter.payroll.presentation.overview

sealed interface PayslipsEffect {
    data object Back : PayslipsEffect

    data object VerifyAccount : PayslipsEffect

    data object RecheckWorkspace : PayslipsEffect

    data class OpenPayslip(val id: String) : PayslipsEffect
}
