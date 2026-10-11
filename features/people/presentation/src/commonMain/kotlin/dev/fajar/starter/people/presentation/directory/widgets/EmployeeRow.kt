package dev.fajar.starter.people.presentation.directory.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.people.domain.entities.DirectoryEmployee
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Person

@Composable
fun EmployeeRow(employee: DirectoryEmployee) {
    val position = employee.position
    val department = employee.department
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.size(40.dp).background(AppColors.tint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(Icons.Regular.Person, tint = AppColors.accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                AppText(
                    employee.name,
                    style = FluentTheme.typography.bodyStrong,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (position != null)
                    AppText(position, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (department != null)
                    AppText(
                        department,
                        color = AppColors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                AppText(
                    employee.employeeNumber,
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.border))
    }
}
