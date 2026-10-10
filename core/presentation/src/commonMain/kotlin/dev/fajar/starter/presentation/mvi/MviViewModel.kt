package dev.fajar.starter.presentation.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.reflect.KClass
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Events enter on Main; each typed handler chooses its own concurrency policy. Reducers must be
 * pure. Effects have one route consumer and are buffered while that consumer is stopped. They are
 * transient, not durable application state.
 */
abstract class MviViewModel<State : Any, Event : Any, Effect : Any>(initialState: State) :
    ViewModel() {
    private val mutableState = MutableStateFlow(initialState)
    val state = mutableState.asStateFlow()

    private val effectChannel = Channel<Effect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    private val handlers = mutableMapOf<KClass<out Event>, (Event) -> Unit>()

    fun onEvent(event: Event) {
        viewModelScope.launch {
            checkNotNull(handlers[event::class]) {
                "No handler registered for ${event::class.simpleName}"
            }(event)
        }
    }

    protected inline fun <reified T : Event> on(noinline handler: (T) -> Unit) {
        register(T::class) { event -> handler(event as T) }
    }

    protected fun register(type: KClass<out Event>, handler: (Event) -> Unit) {
        check(type !in handlers) { "An event must have exactly one handler" }
        handlers[type] = handler
    }

    protected fun updateState(reducer: (State) -> State) {
        mutableState.update(reducer)
    }

    protected suspend fun emitEffect(effect: Effect) {
        effectChannel.send(effect)
    }

    override fun onCleared() {
        effectChannel.cancel()
    }
}
