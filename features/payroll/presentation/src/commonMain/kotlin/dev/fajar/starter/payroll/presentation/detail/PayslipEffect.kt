package dev.fajar.starter.payroll.presentation.detail

sealed interface PayslipEffect {
    data object Back : PayslipEffect

    data object VerifyAccount : PayslipEffect

    data object RecheckWorkspace : PayslipEffect
}
