package dev.fajar.starter.people.presentation.profile.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.people.presentation.profile.*
import dev.fajar.starter.people.presentation.profile.widgets.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft

@Composable
fun ProfilePage(state: ProfileState, onEvent: (ProfileEvent) -> Unit) {
    AppLazyPage {
        item(key = "heading") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIconButton(
                    Icons.Regular.ArrowLeft,
                    appString(AppString.Back),
                    { onEvent(ProfileEvent.BackRequested) },
                )
                AppHeading(appString(AppString.MyProfile), Modifier.weight(1f))
                AppButton(
                    appString(AppString.Refresh),
                    { onEvent(ProfileEvent.DirectoryRequested()) },
                    enabled = !state.loadingDirectory,
                    primary = false,
                )
            }
        }
        if (state.loadingDirectory || state.loadingProfile) item(key = "loading") { AppLoading() }
        if (state.failure != null)
            item(key = "failure") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppFeedback(failureText(state.failure))
                    if (state.needsVerification)
                        AppButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(ProfileEvent.VerificationRequested) },
                        )
                    else
                        AppButton(
                            appString(AppString.TryAgain),
                            { onEvent(ProfileEvent.DirectoryRequested()) },
                        )
                }
            }
        if (state.directory?.items?.isEmpty() == true)
            item(key = "empty") { AppText(appString(AppString.NoEmployment)) }
        if (
            state.directory != null &&
                (state.directory.items.size > 1 || state.directory.nextCursor != null)
        ) {
            item(key = "selection") { AppText(appString(AppString.ChooseEmployment)) }
            items(state.directory.items, key = { "employment/${it.id}" }) { employee ->
                AppButton(
                    employee.employeeNumber,
                    { onEvent(ProfileEvent.EmploymentSelected(employee.id)) },
                    Modifier.fillMaxWidth(),
                    primary = state.selectedId == employee.id,
                )
            }
        }
        if (state.directory?.nextCursor != null)
            item(key = "next") {
                AppButton(
                    appString(AppString.MoreEmployments),
                    { onEvent(ProfileEvent.DirectoryRequested(state.directory.nextCursor)) },
                    primary = false,
                )
            }
        if (state.profile != null) {
            item(key = "personal/${state.profile.person.personId}") {
                PersonalDetails(state.profile.person)
            }
            item(key = "work/${state.profile.details.employee.id}") {
                EmploymentOverview(state.profile.details)
            }
        }
    }
}
