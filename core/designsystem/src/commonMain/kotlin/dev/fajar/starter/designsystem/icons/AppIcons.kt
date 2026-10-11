package dev.fajar.starter.designsystem.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small application glyphs that do not require the full extended icon dependency. */
object AppIcons {
    val Services: ImageVector =
        ImageVector.Builder("Services", 24.dp, 24.dp, 24f, 24f)
            .apply {
                path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f) {
                    moveTo(4f, 4f)
                    lineTo(10f, 4f)
                    lineTo(10f, 10f)
                    lineTo(4f, 10f)
                    close()
                    moveTo(14f, 4f)
                    lineTo(20f, 4f)
                    lineTo(20f, 10f)
                    lineTo(14f, 10f)
                    close()
                    moveTo(4f, 14f)
                    lineTo(10f, 14f)
                    lineTo(10f, 20f)
                    lineTo(4f, 20f)
                    close()
                    moveTo(14f, 14f)
                    lineTo(20f, 14f)
                    lineTo(20f, 20f)
                    lineTo(14f, 20f)
                    close()
                }
            }
            .build()
}
