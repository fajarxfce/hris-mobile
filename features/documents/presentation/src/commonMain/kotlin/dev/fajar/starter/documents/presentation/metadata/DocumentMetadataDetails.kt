package dev.fajar.starter.documents.presentation.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.documents.domain.entities.DocumentBusinessMetadata
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun DocumentMetadataDetails(metadata: DocumentBusinessMetadata) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MetadataValue(AppString.DocumentBusinessCategory, appString(metadata.category.label()))
        metadata.identityNumber?.let { MetadataValue(AppString.DocumentNumber, it) }
        metadata.issuingCountry?.let { MetadataValue(AppString.DocumentIssuingCountry, it) }
        metadata.issuedOn?.let { MetadataValue(AppString.DocumentIssuedOn, it.toString()) }
        metadata.validUntil?.let { MetadataValue(AppString.DocumentValidUntil, it.toString()) }
    }
}

@Composable
private fun MetadataValue(label: AppString, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppText(appString(label), color = AppColors.muted, style = FluentTheme.typography.caption)
        AppText(value)
    }
}
