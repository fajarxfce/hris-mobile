package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Decorative illustration, drawn locally without network assets. */
@Composable
fun AppWorkspaceIllustration(step: Int = 0, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(230.dp)
            .background(Color(0xFFEAF3FC), RoundedCornerShape(24.dp))
    ) {
        val w = size.width
        val h = size.height
        drawCircle(Color(0xFFD5E7FA), h * 0.46f, Offset(w * 0.82f, h * 0.26f))
        drawCircle(Color(0xFFDAECFB), h * 0.38f, Offset(w * 0.12f, h * 0.92f))
        drawRoundRect(
            Color(0xFFCFDDED),
            Offset(w * 0.12f, h * 0.18f),
            Size(w * 0.76f, h * 0.7f),
            CornerRadius(16f),
        )
        drawRoundRect(
            Color.White,
            Offset(w * 0.12f, h * 0.14f),
            Size(w * 0.76f, h * 0.7f),
            CornerRadius(16f),
        )
        drawRoundRect(
            Color(0xFF0067B8),
            Offset(w * 0.17f, h * 0.22f),
            Size(w * 0.09f, h * 0.1f),
            CornerRadius(6f),
        )
        drawRoundRect(
            Color(0xFF253A56),
            Offset(w * 0.3f, h * 0.235f),
            Size(w * 0.27f, h * 0.025f),
            CornerRadius(3f),
        )
        drawRoundRect(
            Color(0xFFD8E1EE),
            Offset(w * 0.3f, h * 0.285f),
            Size(w * 0.17f, h * 0.018f),
            CornerRadius(3f),
        )
        repeat(3) { index ->
            val x = w * (0.17f + index * 0.23f)
            drawRoundRect(
                Color(0xFFF0F5FA),
                Offset(x, h * 0.4f),
                Size(w * 0.2f, h * 0.19f),
                CornerRadius(9f),
            )
            drawRoundRect(
                if (index == step) Color(0xFF0067B8) else Color(0xFFB2CCE5),
                Offset(x + w * 0.03f, h * 0.46f),
                Size(w * 0.09f, h * 0.04f),
                CornerRadius(3f),
            )
        }
        drawRoundRect(
            Color(0xFFDFE8F2),
            Offset(w * 0.17f, h * 0.66f),
            Size(w * 0.54f, h * 0.025f),
            CornerRadius(4f),
        )
        drawRoundRect(
            Color(0xFFEBF0F6),
            Offset(w * 0.17f, h * 0.725f),
            Size(w * 0.4f, h * 0.025f),
            CornerRadius(4f),
        )
    }
}
