package dev.fajar.starter.app.bootstrap

import androidx.compose.runtime.Composable
import dev.fajar.starter.designsystem.components.AppBrand
import dev.fajar.starter.designsystem.components.AppButton
import dev.fajar.starter.designsystem.components.AppFeedback
import dev.fajar.starter.designsystem.components.AppLoading
import dev.fajar.starter.designsystem.components.AppPage
import dev.fajar.starter.localization.AppString
import dev.fajar.starter.localization.appString
import dev.fajar.starter.localization.failureText

@Composable
fun BootstrapPage(stage: AppStage, onRetry: () -> Unit) {
    AppPage {
        AppBrand()
        when (stage) {
            is AppStage.Failed -> {
                AppFeedback(failureText(stage.failure))
                AppButton(appString(AppString.TryAgain), onRetry)
            }
            else -> AppLoading()
        }
    }
}
