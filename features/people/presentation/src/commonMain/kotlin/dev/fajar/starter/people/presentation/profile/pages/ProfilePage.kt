package dev.fajar.starter.people.presentation.profile.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.people.presentation.profile.*
import dev.fajar.starter.people.presentation.profile.widgets.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.Person

@Composable
fun ProfilePage(
    state: ProfileState,
    employmentList: LazyListState,
    onEvent: (ProfileEvent) -> Unit,
) {
    val profile = state.view?.profile
    val failure = state.failure
    AppLazyPage(
        maxWidth = 720.dp,
        spacing = 16.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                appString(AppString.MyProfile),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(ProfileEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(ProfileEvent.RefreshRequested) },
                        enabled = !state.syncing,
                    )
                },
            )
        },
    ) {
        if (state.loading || state.syncing) item(key = "loading") { AppLoading() }
        if (failure != null)
            item(key = "failure") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppFeedback(failureText(failure))
                    if (state.needsVerification)
                        AppButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(ProfileEvent.VerificationRequested) },
                        )
                    else
                        AppTextButton(
                            appString(AppString.TryAgain),
                            { onEvent(ProfileEvent.RefreshRequested) },
                            enabled = !state.syncing,
                        )
                }
            }
        if (state.view?.employments?.isEmpty() == true)
            item(key = "empty") { AppText(appString(AppString.NoEmployment)) }
        if (state.view != null && state.view.employments.size > 1)
            item(key = "selection") {
                AppFilterChip(
                    profile?.details?.employee?.employeeNumber
                        ?: appString(AppString.ChooseEmployment),
                    selected = state.view.selectedId != null,
                    onClick = { onEvent(ProfileEvent.EmploymentRequested) },
                    icon = Icons.Regular.Person,
                )
            }
        if (state.syncPending && !state.syncing && state.view == null && failure == null)
            item(key = "pending") { AppText(appString(AppString.SyncPending)) }
        if (profile != null) {
            item(key = "personal/${profile.person.personId}") { PersonalDetails(profile.person) }
            item(key = "work/${profile.details.employee.id}") {
                EmploymentOverview(profile.details)
            }
        }
    }
    if (state.choosingEmployment) ProfileEmploymentDialog(state, employmentList, onEvent)
}
