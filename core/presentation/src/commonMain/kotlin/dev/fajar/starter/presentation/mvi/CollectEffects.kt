package dev.fajar.starter.presentation.mvi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow

/** Install once in the route, never in individual widgets. */
@Composable
fun <Effect : Any> CollectEffects(effects: Flow<Effect>, onEffect: (Effect) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val currentHandler by rememberUpdatedState(onEffect)
    LaunchedEffect(effects, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            effects.collect { currentHandler(it) }
        }
    }
}
