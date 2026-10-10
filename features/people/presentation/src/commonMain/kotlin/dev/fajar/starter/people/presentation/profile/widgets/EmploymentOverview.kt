package dev.fajar.starter.people.presentation.profile.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.people.domain.entities.*
import io.github.composefluent.FluentTheme

@Composable
fun EmploymentOverview(details: EmploymentDetails) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppHeading(appString(AppString.Employment), style = FluentTheme.typography.subtitle)
            ProfileField(appString(AppString.EmployeeNumber), details.employee.employeeNumber)
            ProfileField(
                appString(AppString.EmploymentStatus),
                appString(
                    when (details.employee.status) {
                        EmploymentStatus.Active -> AppString.StatusActive
                        EmploymentStatus.Probation -> AppString.StatusProbation
                        EmploymentStatus.Suspended -> AppString.StatusSuspended
                        EmploymentStatus.Ended -> AppString.StatusEnded
                    }
                ),
            )
            ProfileField(
                appString(AppString.Contract),
                appString(
                    when (details.employee.contract) {
                        EmploymentContract.Permanent -> AppString.ContractPermanent
                        EmploymentContract.FixedTerm -> AppString.ContractFixedTerm
                    }
                ),
            )
            ProfileField(appString(AppString.StartDate), details.employee.startDate.toString())
            ProfileField(appString(AppString.EndDate), details.employee.endDate?.toString())
            ProfileField(appString(AppString.Position), details.position?.name)
            ProfileField(appString(AppString.Department), details.department?.name)
            ProfileField(appString(AppString.Branch), details.branch?.name)
            ProfileField(appString(AppString.CostCenter), details.costCenter?.name)
            ProfileField(appString(AppString.Manager), details.manager?.legalName)
            AppText(appString(AppString.EmploymentAsOf, details.asOf.toString()))
        }
    }
}
