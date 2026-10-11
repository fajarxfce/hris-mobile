package dev.fajar.starter.home.presentation.home.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Person

@Composable
fun EmployeeWelcomeCard(name: String, company: String) {
    AppCard(Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).background(AppColors.tint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(Icons.Regular.Person, tint = AppColors.accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText(
                    appString(AppString.HomeGreeting),
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
                AppHeading(name, style = FluentTheme.typography.subtitle)
                AppText(company, color = AppColors.muted)
            }
        }
    }
}
