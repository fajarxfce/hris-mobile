package dev.fajar.starter.home.presentation.datasync

import dev.fajar.starter.localization.AppString
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet

val CompanyDataSet.title: AppString
    get() =
        when (this) {
            CompanyDataSet.Profile -> AppString.MyProfile
            CompanyDataSet.Employees -> AppString.Employees
            CompanyDataSet.Inbox -> AppString.Inbox
            CompanyDataSet.Workforce -> AppString.DataSyncWorkforce
            CompanyDataSet.Overtime -> AppString.Overtime
            CompanyDataSet.Leave -> AppString.Leave
            CompanyDataSet.Documents -> AppString.Documents
            CompanyDataSet.Expenses -> AppString.Expenses
            CompanyDataSet.Payroll -> AppString.Payslips
        }
