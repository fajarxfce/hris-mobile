package dev.fajar.starter.payroll.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun PayslipAmountRow(label: String, amount: DecimalAmount, emphasized: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        FlowRow(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            maxItemsInEachRow =
                if (maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f) 1 else 2,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppText(
                label,
                Modifier.weight(1f),
                style =
                    if (emphasized) FluentTheme.typography.bodyStrong
                    else FluentTheme.typography.body,
            )
            AppText(
                formatDecimal(amount, appLanguageTag()),
                Modifier.weight(1f),
                textAlign = TextAlign.End,
                style =
                    (if (emphasized) FluentTheme.typography.bodyStrong
                        else FluentTheme.typography.body)
                        .copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}
