package dev.fajar.starter.home.presentation.pending

import dev.fajar.starter.localization.AppString
import dev.fajar.starter.workspace.domain.entities.PendingActionGroup

val PendingActionGroup.title: AppString
    get() =
        when (this) {
            PendingActionGroup.Inbox -> AppString.Inbox
            PendingActionGroup.Attendance -> AppString.Attendance
            PendingActionGroup.Overtime -> AppString.Overtime
            PendingActionGroup.Leave -> AppString.Leave
            PendingActionGroup.Expenses -> AppString.Expenses
            PendingActionGroup.Documents -> AppString.Documents
            PendingActionGroup.DocumentCorrections -> AppString.DocumentMetadataChanges
        }
