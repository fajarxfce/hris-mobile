package dev.fajar.starter.workforce.presentation.overtime.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.AppFilterChip
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.overtime.formatters.label
import dev.fajar.starter.workforce.presentation.overtime.overview.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Person

/** Filters keep their position while the independently scrolling results refresh or fail. */
@Composable
fun OvertimeFilters(state: OvertimeState, onEvent: (OvertimeEvent) -> Unit) {
    val filters = state.filters
    val content = state.content?.value
    if (!state.readRestricted && filters != null) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.month?.let { OvertimeMonthBar(it, onEvent) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (filters.employeeId != null)
                    AppFilterChip(
                        appString(filters.status.label()),
                        filters.status != null,
                        { onEvent(OvertimeEvent.StatusFilterRequested) },
                    )
                if (
                    content != null &&
                        (content.directory.items.size > 1 ||
                            content.directory.nextCursor != null ||
                            filters.employmentAfter != null)
                )
                    AppFilterChip(
                        appString(AppString.ChooseEmployment),
                        true,
                        { onEvent(OvertimeEvent.EmploymentFilterRequested) },
                        icon = Icons.Regular.Person,
                    )
            }
        }
    }
}
