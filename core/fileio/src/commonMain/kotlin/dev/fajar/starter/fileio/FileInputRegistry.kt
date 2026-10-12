package dev.fajar.starter.fileio

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * Bounded SDK-handle ownership only. It performs no I/O, admission, retries or source selection.
 */
internal class FileInputRegistry<T : Any> {
    private val state = MutableStateFlow(Snapshot<T>())

    fun reserve(id: String, operation: Job): Any {
        require(id.length in 1..128)
        val ticket = Any()
        state.update { current ->
            if (current.closed) throw FileInputUnavailableException()
            if (id in current.inputs || current.inputs.values.any { it.selection != null })
                throw FileInputBusyException()
            if (current.inputs.size >= 4) throw FileInputCapacityException()
            if (!operation.isActive) throw FileInputReleasedException()
            current.copy(inputs = current.inputs + (id to Input<T>(ticket, operation)))
        }
        return ticket
    }

    fun publish(id: String, ticket: Any, value: T) {
        state.update { current ->
            val input = current.inputs[id] ?: throw FileInputReleasedException()
            if (input.ticket !== ticket || input.selection?.isActive != true)
                throw FileInputReleasedException()
            current.copy(
                inputs = current.inputs + (id to input.copy(selection = null, value = value))
            )
        }
    }

    fun beginRead(id: String, operation: Job): T {
        val current =
            state.updateAndGet { current ->
                val input = current.inputs[id] ?: throw FileInputReleasedException()
                if (input.value == null) throw FileInputReleasedException()
                if (input.reader != null) throw FileInputBusyException()
                if (!operation.isActive) throw FileInputReleasedException()
                current.copy(inputs = current.inputs + (id to input.copy(reader = operation)))
            }
        return requireNotNull(current.inputs[id]?.value)
    }

    fun endRead(id: String, operation: Job) {
        state.update { current ->
            val input = current.inputs[id]
            if (input == null || input.reader !== operation) current
            else current.copy(inputs = current.inputs + (id to input.copy(reader = null)))
        }
    }

    /** Selection cleanup cannot remove a later registration reusing the same request ID. */
    fun discardSelection(id: String, ticket: Any): T? {
        val previous =
            state.getAndUpdate { current ->
                if (current.inputs[id]?.ticket !== ticket) current
                else current.copy(inputs = current.inputs - id)
            }
        return previous.inputs[id]?.takeIf { it.ticket === ticket }?.value
    }

    fun release(id: String): T? {
        val input =
            state.getAndUpdate { it.copy(inputs = it.inputs - id) }.inputs[id] ?: return null
        input.selection?.cancel()
        input.reader?.cancel()
        return input.value
    }

    fun cancelSelections() {
        state.value.inputs.values.forEach { it.selection?.cancel() }
    }

    fun close(): List<T> {
        val previous = state.getAndUpdate { Snapshot<T>(closed = true) }
        previous.inputs.values.forEach {
            it.selection?.cancel()
            it.reader?.cancel()
        }
        return previous.inputs.values.mapNotNull { it.value }
    }

    private data class Input<T>(
        val ticket: Any,
        val selection: Job? = null,
        val value: T? = null,
        val reader: Job? = null,
    )

    private data class Snapshot<T>(
        val closed: Boolean = false,
        val inputs: Map<String, Input<T>> = emptyMap(),
    )
}
