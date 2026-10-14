package dev.fajar.starter.people.presentation.profile.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.people.presentation.profile.*

@Composable
fun ProfileEmploymentDialog(
    state: ProfileState,
    list: LazyListState,
    onEvent: (ProfileEvent) -> Unit,
) {
    AppDialog(
        appString(AppString.ChooseEmployment),
        appString(AppString.Close),
        { onEvent(ProfileEvent.EmploymentDismissed) },
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.view?.employments.orEmpty(), key = { it.id }) { employment ->
                AppRadioRow(
                    employment.employeeNumber,
                    state.view?.selectedId == employment.id,
                    { onEvent(ProfileEvent.EmploymentSelected(employment.id)) },
                    enabled = !state.readRestricted,
                )
            }
        }
    }
}
