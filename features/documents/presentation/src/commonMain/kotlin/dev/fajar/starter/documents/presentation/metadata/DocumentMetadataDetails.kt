package dev.fajar.starter.documents.presentation.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.documents.domain.entities.DocumentBusinessMetadata
import dev.fajar.starter.localization.*

@Composable
fun DocumentMetadataDetails(metadata: DocumentBusinessMetadata) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MetadataValue(AppString.DocumentBusinessCategory, appString(metadata.category.label()))
        metadata.identityNumber?.let { MetadataValue(AppString.DocumentNumberLabel, it) }
        metadata.issuingCountry?.let { MetadataValue(AppString.DocumentIssuingCountryLabel, it) }
        metadata.issuedOn?.let {
            MetadataValue(
                AppString.DocumentIssuedOnLabel,
                formatLocalDate(it.toString(), appLanguageTag()),
            )
        }
        metadata.validUntil?.let {
            MetadataValue(
                AppString.DocumentValidUntilLabel,
                formatLocalDate(it.toString(), appLanguageTag()),
            )
        }
    }
}

@Composable
private fun MetadataValue(label: AppString, value: String) {
    AppDetailField(appString(label), value)
}
