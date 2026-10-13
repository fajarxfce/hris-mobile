package dev.fajar.starter.home.presentation.catalog

import androidx.compose.ui.graphics.vector.ImageVector
import dev.fajar.starter.localization.AppString
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.companyModuleAvailable
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.CalendarCheckmark
import io.github.composefluent.icons.regular.CalendarClock
import io.github.composefluent.icons.regular.CalendarLtr
import io.github.composefluent.icons.regular.Clock
import io.github.composefluent.icons.regular.Document
import io.github.composefluent.icons.regular.Person
import io.github.composefluent.icons.regular.Receipt

/** A bounded catalog of implemented services; destination state stays with each feature. */
enum class HomeService(val title: AppString, val summary: AppString, val icon: ImageVector) {
    Attendance(AppString.Attendance, AppString.AttendanceSummary, Icons.Regular.Clock),
    Calendar(AppString.WorkCalendar, AppString.CalendarSummary, Icons.Regular.CalendarLtr),
    Leave(AppString.Leave, AppString.LeaveSummary, Icons.Regular.CalendarCheckmark),
    Overtime(AppString.Overtime, AppString.OvertimeSummary, Icons.Regular.CalendarClock),
    Documents(AppString.Documents, AppString.DocumentsSummary, Icons.Regular.Document),
    Payslips(AppString.Payslips, AppString.PayslipsSummary, Icons.Regular.Receipt),
    Profile(AppString.MyProfile, AppString.ProfileSummary, Icons.Regular.Person),
}

data class HomeServiceSection(val title: AppString, val items: List<HomeService>)

data class HomeServiceCatalog(
    val sections: List<HomeServiceSection> = emptyList(),
    val shortcuts: List<HomeService> = emptyList(),
    val account: Set<HomeService> = emptySet(),
)

fun homeServices(workspace: Workspace?): HomeServiceCatalog {
    if (workspace == null) return HomeServiceCatalog()
    val permissions = workspace.permissions
    val work = buildList {
        if (companyModuleAvailable(workspace, CompanyModule.WORKFORCE)) {
            if ("attendance.self.record" in permissions || "workforce.read" in permissions) {
                add(HomeService.Attendance)
                add(HomeService.Calendar)
            }
            if (
                permissions.any {
                    it in
                        setOf(
                            "overtime.self.manage",
                            "overtime.read",
                            "overtime.manage",
                            "workforce.read",
                        )
                }
            )
                add(HomeService.Overtime)
        }
        if (
            companyModuleAvailable(workspace, CompanyModule.LEAVE) &&
                ("leave.self.manage" in permissions || "leave.read" in permissions)
        )
            add(HomeService.Leave)
    }
    val personal = buildList {
        if (
            companyModuleAvailable(workspace, CompanyModule.PAYROLL) &&
                permissions.any { it == "payroll.self.read" || it == "payroll.read" }
        )
            add(HomeService.Payslips)
        if (
            companyModuleAvailable(workspace, CompanyModule.DOCUMENTS) &&
                "documents.self.read" in permissions
        )
            add(HomeService.Documents)
    }
    val sections =
        listOf(
                HomeServiceSection(AppString.ServicesWork, work),
                HomeServiceSection(AppString.ServicesPersonal, personal),
            )
            .filter { it.items.isNotEmpty() }
    val account =
        if (
            "people.self.read" in permissions &&
                companyModuleAvailable(workspace, CompanyModule.PEOPLE)
        )
            setOf(HomeService.Profile)
        else emptySet()
    return HomeServiceCatalog(sections, sections.flatMap { it.items }.take(4), account)
}
