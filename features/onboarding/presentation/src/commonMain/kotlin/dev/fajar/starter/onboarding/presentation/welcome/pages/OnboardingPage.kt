package dev.fajar.starter.onboarding.presentation.welcome.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.onboarding.presentation.welcome.OnboardingContent
import dev.fajar.starter.onboarding.presentation.welcome.OnboardingEvent
import dev.fajar.starter.onboarding.presentation.welcome.OnboardingState
import io.github.composefluent.FluentTheme

@Composable
fun OnboardingPage(state: OnboardingState, onEvent: (OnboardingEvent) -> Unit) {
    AppPage(maxWidth = 500.dp) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AppBrand()
            AppButton(
                appString(AppString.Skip),
                { onEvent(OnboardingEvent.FinishRequested) },
                primary = false,
                enabled = !state.saving,
            )
        }
        Spacer(Modifier.height(16.dp))
        AppWorkspaceIllustration(state.step)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppText(
                appString(OnboardingContent.pages[state.step].title),
                style = FluentTheme.typography.titleLarge,
            )
            AppText(
                appString(OnboardingContent.pages[state.step].description),
                style = FluentTheme.typography.bodyLarge,
                color = AppColors.muted,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                Box(
                    Modifier.size(if (index == state.step) 10.dp else 7.dp)
                        .background(
                            if (index == state.step) AppColors.accent else AppColors.border,
                            CircleShape,
                        )
                )
            }
            Spacer(Modifier.width(8.dp))
            AppText(
                appString(AppString.StepOf, state.step + 1, 3),
                style = FluentTheme.typography.caption,
                color = AppColors.muted,
            )
        }
        if (state.error != null) AppFeedback(failureText(state.error))
        AppButton(
            if (state.step == 2) appString(AppString.GetStarted) else appString(AppString.Continue),
            { onEvent(OnboardingEvent.NextRequested) },
            Modifier.fillMaxWidth(),
            loading = state.saving,
        )
        if (state.step > 0)
            AppButton(
                appString(AppString.Back),
                { onEvent(OnboardingEvent.BackRequested) },
                Modifier.fillMaxWidth(),
                primary = false,
                enabled = !state.saving,
            )
    }
}
