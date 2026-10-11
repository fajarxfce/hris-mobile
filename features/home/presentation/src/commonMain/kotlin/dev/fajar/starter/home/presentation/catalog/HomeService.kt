package dev.fajar.starter.home.presentation.catalog

import androidx.compose.ui.graphics.vector.ImageVector
import dev.fajar.starter.localization.AppString
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.companyModuleAvailable
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.CalendarLtr
import io.github.composefluent.icons.regular.Clock
import io.github.composefluent.icons.regular.Person

/** A bounded catalog of implemented services; destination state stays with each feature. */
enum class HomeService(val title: AppString, val summary: AppString, val icon: ImageVector) {
    Attendance(AppString.Attendance, AppString.AttendanceSummary, Icons.Regular.Clock),
    Calendar(AppString.WorkCalendar, AppString.CalendarSummary, Icons.Regular.CalendarLtr),
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
    val work =
        if (companyModuleAvailable(workspace, CompanyModule.WORKFORCE))
            buildList {
                if ("attendance.self.record" in permissions || "workforce.read" in permissions) {
                    add(HomeService.Attendance)
                    add(HomeService.Calendar)
                }
            }
        else emptyList()
    val sections =
        listOf(HomeServiceSection(AppString.ServicesWork, work)).filter { it.items.isNotEmpty() }
    val account =
        if (
            "people.self.read" in permissions &&
                companyModuleAvailable(workspace, CompanyModule.PEOPLE)
        )
            setOf(HomeService.Profile)
        else emptySet()
    return HomeServiceCatalog(sections, sections.flatMap { it.items }.take(4), account)
}
