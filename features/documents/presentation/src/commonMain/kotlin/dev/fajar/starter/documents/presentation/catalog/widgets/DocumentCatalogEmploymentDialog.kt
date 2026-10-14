package dev.fajar.starter.documents.presentation.catalog.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.documents.presentation.catalog.*
import dev.fajar.starter.localization.*

@Composable
fun DocumentCatalogEmploymentDialog(
    state: DocumentCatalogState,
    list: LazyListState,
    onEvent: (DocumentCatalogEvent) -> Unit,
) {
    AppDialog(
        appString(AppString.ChooseEmployment),
        appString(AppString.Close),
        { onEvent(DocumentCatalogEvent.EmploymentDismissed) },
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.failure?.let { failure ->
                item(key = "failure") { AppFeedback(failureText(failure)) }
            }
            if (state.employments?.isEmpty() == true)
                item(key = "empty") { AppText(appString(AppString.NoEmployment)) }
            items(state.employments.orEmpty(), key = { it.id }) { employment ->
                AppRadioRow(
                    employment.employeeNumber,
                    state.employment?.id == employment.id,
                    { onEvent(DocumentCatalogEvent.EmploymentSelected(employment.id)) },
                    enabled = state.canChoose,
                )
            }
        }
    }
}
