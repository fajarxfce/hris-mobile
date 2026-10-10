package dev.fajar.starter.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.fajar.starter.app.bootstrap.AppViewModelOwner
import dev.fajar.starter.app.navigation.AppNavigation
import dev.fajar.starter.availability.presentation.navigation.AvailabilityGate
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.security.presentation.navigation.AppLockGate
import dev.fajar.starter.settings.presentation.navigation.ProvideAppLanguage
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication

@Composable
fun StarterApp(
    container: KoinApplication,
    incomingLinks: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow(),
) {
    KoinIsolatedContext(context = container) {
        AppViewModelOwner {
            ProvideAppLanguage {
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
                        AvailabilityGate { AppLockGate { AppNavigation(incomingLinks) } }
                    }
                }
            }
        }
    }
}
