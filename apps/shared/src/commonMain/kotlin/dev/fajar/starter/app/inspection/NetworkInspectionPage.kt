package dev.fajar.starter.app.inspection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.inspector.NetworkTraceSnapshot
import dev.fajar.inspector.compose.NetworkTraceDetail
import dev.fajar.inspector.compose.NetworkTraceFilter
import dev.fajar.inspector.compose.NetworkTraceList
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft

/** Bounded app placement around the reusable inspector; networking stays with the app owner. */
@Composable
fun NetworkInspectionPage(
    snapshot: NetworkTraceSnapshot,
    selectedId: Long?,
    filter: NetworkTraceFilter,
    onFilterChange: (NetworkTraceFilter) -> Unit,
    onTraceSelected: (Long) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors =
        (if (FluentTheme.colors.darkMode) darkColorScheme() else lightColorScheme()).copy(
            primary = AppColors.accent,
            onPrimary = AppColors.onAccent,
            background = AppColors.canvas,
            onBackground = AppColors.text,
            surface = AppColors.surface,
            onSurface = AppColors.text,
            surfaceVariant = AppColors.tint,
            onSurfaceVariant = AppColors.muted,
            outline = AppColors.border,
            error = AppColors.error,
        )
    Box(
        modifier
            .fillMaxSize()
            .background(AppColors.canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 960.dp).fillMaxSize()) {
            AppTopBar(
                title = appString(AppString.NetworkInspectionTitle),
                navigation = {
                    AppIconButton(Icons.Regular.ArrowLeft, appString(AppString.Back), onBack)
                },
            )
            MaterialTheme(colorScheme = colors) {
                if (selectedId == null) {
                    NetworkTraceList(
                        snapshot,
                        filter,
                        onFilterChange,
                        onTraceSelected,
                        Modifier.weight(1f),
                        actions = {
                            AppTextButton(
                                appString(AppString.NetworkInspectionClear),
                                onClear,
                                enabled = !snapshot.closed,
                            )
                        },
                    )
                } else {
                    NetworkTraceDetail(
                        snapshot.traces.firstOrNull { it.id == selectedId },
                        Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
