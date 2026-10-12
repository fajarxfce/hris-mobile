package dev.fajar.starter.documents.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.documents.presentation.detail.*
import dev.fajar.starter.localization.*

@Composable
fun DocumentEmploymentDialog(
    state: DocumentFileState,
    list: LazyListState,
    onEvent: (DocumentFileEvent) -> Unit,
) {
    AppDialog(
        appString(AppString.ChooseEmployment),
        appString(AppString.Close),
        { onEvent(DocumentFileEvent.EmploymentDismissed) },
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.formLoading) item(key = "loading") { AppLoading(compact = true) }
            state.formFailure?.let { failure ->
                item(key = "failure") { AppFeedback(failureText(failure)) }
            }
            if (state.choices?.employments?.items?.isEmpty() == true)
                item(key = "empty") { AppText(appString(AppString.NoEmployment)) }
            items(state.choices?.employments?.items.orEmpty(), key = { it.id }) { employment ->
                AppRadioRow(
                    employment.employeeNumber,
                    state.form?.employmentId == employment.id,
                    { onEvent(DocumentFileEvent.EmploymentSelected(employment.id)) },
                    enabled = !state.formLoading,
                )
            }
            item(key = "paging") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextButton(
                        appString(AppString.CalendarFirstEmployments),
                        { onEvent(DocumentFileEvent.EmploymentPageRequested(null)) },
                        enabled = !state.formLoading,
                    )
                    state.choices?.employments?.nextCursor?.let { after ->
                        AppTextButton(
                            appString(AppString.MoreEmployments),
                            { onEvent(DocumentFileEvent.EmploymentPageRequested(after)) },
                            enabled = !state.formLoading,
                        )
                    }
                }
            }
        }
    }
}
