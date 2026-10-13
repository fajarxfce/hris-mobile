package dev.fajar.starter.payroll.presentation.overview

sealed interface PayslipsEvent {
    data object Entered : PayslipsEvent

    data object Stopped : PayslipsEvent

    data object RefreshRequested : PayslipsEvent

    data object PreviousYearRequested : PayslipsEvent

    data object NextYearRequested : PayslipsEvent

    data class PageRequested(val after: String?) : PayslipsEvent

    data class PayslipSelected(val id: String) : PayslipsEvent

    data object BackRequested : PayslipsEvent

    data object VerificationRequested : PayslipsEvent
}
