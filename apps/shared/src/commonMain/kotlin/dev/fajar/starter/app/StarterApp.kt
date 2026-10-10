package dev.fajar.starter.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.fajar.starter.app.bootstrap.AppViewModelOwner
import dev.fajar.starter.app.navigation.AppNavigation
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.designsystem.theme.AppTheme
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication

@Composable
fun StarterApp(container: KoinApplication) {
    KoinIsolatedContext(context = container) {
        AppViewModelOwner {
            AppTheme {
                Box(
                    Modifier.fillMaxSize()
                        .background(AppColors.canvas)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                            )
                        )
                        .imePadding()
                ) {
                    AppNavigation()
                }
            }
        }
    }
}
