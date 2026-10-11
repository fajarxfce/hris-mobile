package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors

@Composable
fun AppLazyPage(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    spacing: Dp = 24.dp,
    contentPadding: PaddingValues = PaddingValues(24.dp),
    maxWidth: Dp = 960.dp,
    topBar: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(AppColors.canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = maxWidth).fillMaxSize()) {
            topBar()
            LazyColumn(
                state = state,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(spacing),
                content = content,
            )
        }
    }
}
