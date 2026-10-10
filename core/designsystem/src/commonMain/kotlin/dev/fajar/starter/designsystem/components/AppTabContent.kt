package dev.fajar.starter.designsystem.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import dev.fajar.starter.designsystem.theme.LocalAppMotion

/** Retains rendering state for a finite set of tabs within the owning navigation/session scope. */
@Composable
fun <T : Any> AppTabContent(
    selected: T,
    stateKey: (T) -> String,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    val motion = LocalAppMotion.current
    AnimatedContent(
        targetState = selected,
        modifier = modifier,
        contentKey = stateKey,
        transitionSpec = {
            (fadeIn(tween(motion.tabMillis)) togetherWith fadeOut(tween(motion.tabMillis))).using(
                null
            )
        },
        label = "tab content",
    ) { target ->
        holder.SaveableStateProvider(stateKey(target)) { content(target) }
    }
}
