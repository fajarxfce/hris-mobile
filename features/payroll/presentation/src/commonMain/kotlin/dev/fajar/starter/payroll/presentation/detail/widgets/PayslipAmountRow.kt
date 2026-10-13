package dev.fajar.starter.payroll.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun PayslipAmountRow(label: String, amount: DecimalAmount, emphasized: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AppText(
            label,
            Modifier.weight(1f),
            style =
                if (emphasized) FluentTheme.typography.bodyStrong else FluentTheme.typography.body,
        )
        AppText(
            formatDecimal(amount, appLanguageTag()),
            Modifier.weight(1f),
            textAlign = TextAlign.End,
            style =
                (if (emphasized) FluentTheme.typography.bodyStrong else FluentTheme.typography.body)
                    .copy(fontFeatureSettings = "tnum"),
        )
    }
}
