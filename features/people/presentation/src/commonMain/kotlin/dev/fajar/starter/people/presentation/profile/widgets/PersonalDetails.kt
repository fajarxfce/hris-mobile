package dev.fajar.starter.people.presentation.profile.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.people.domain.entities.PersonProfile
import io.github.composefluent.FluentTheme

@Composable
fun PersonalDetails(person: PersonProfile) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppHeading(
                appString(AppString.PersonalInformation),
                style = FluentTheme.typography.subtitle,
            )
            ProfileField(appString(AppString.FullName), person.legalName)
            ProfileField(appString(AppString.Email), person.email)
            ProfileField(appString(AppString.BirthDate), person.birthDate?.toString())
            ProfileField(appString(AppString.Nationality), person.nationality)
        }
    }
}
