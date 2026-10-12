package dev.fajar.starter.fileio

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * Bounded SDK-handle ownership only. It performs no I/O, admission, retries or source acquisition.
 */
internal class FileHandleRegistry<T : Any> {
    private val state = MutableStateFlow(Snapshot<T>())

    fun reserve(id: String, operation: Job): Any {
        require(id.length in 1..128)
        val ticket = Any()
        state.update { current ->
            if (current.closed) throw FileUnavailableException()
            if (id in current.handles || current.handles.values.any { it.acquisition != null })
                throw FileBusyException()
            if (current.handles.size >= 4) throw FileCapacityException()
            if (!operation.isActive) throw FileReleasedException()
            current.copy(handles = current.handles + (id to Handle<T>(ticket, operation)))
        }
        return ticket
    }

    fun publish(id: String, ticket: Any, value: T) {
        state.update { current ->
            val handle = current.handles[id] ?: throw FileReleasedException()
            if (handle.ticket !== ticket || handle.acquisition?.isActive != true)
                throw FileReleasedException()
            current.copy(
                handles = current.handles + (id to handle.copy(acquisition = null, value = value))
            )
        }
    }

    fun beginOperation(id: String, operation: Job): T {
        val current =
            state.updateAndGet { current ->
                val handle = current.handles[id] ?: throw FileReleasedException()
                if (handle.value == null) throw FileReleasedException()
                if (handle.operation != null) throw FileBusyException()
                if (!operation.isActive) throw FileReleasedException()
                current.copy(handles = current.handles + (id to handle.copy(operation = operation)))
            }
        return requireNotNull(current.handles[id]?.value)
    }

    fun endOperation(id: String, operation: Job) {
        state.update { current ->
            val handle = current.handles[id]
            if (handle == null || handle.operation !== operation) current
            else current.copy(handles = current.handles + (id to handle.copy(operation = null)))
        }
    }

    /** Selection cleanup cannot remove a later registration reusing the same request ID. */
    fun discard(id: String, ticket: Any): T? {
        val previous =
            state.getAndUpdate { current ->
                if (current.handles[id]?.ticket !== ticket) current
                else current.copy(handles = current.handles - id)
            }
        return previous.handles[id]?.takeIf { it.ticket === ticket }?.value
    }

    fun release(id: String): T? {
        val handle =
            state.getAndUpdate { it.copy(handles = it.handles - id) }.handles[id] ?: return null
        handle.acquisition?.cancel()
        handle.operation?.cancel()
        return handle.value
    }

    fun cancelAcquisitions() {
        state.value.handles.values.forEach { it.acquisition?.cancel() }
    }

    fun close(): List<T> {
        val previous = state.getAndUpdate { Snapshot<T>(closed = true) }
        previous.handles.values.forEach {
            it.acquisition?.cancel()
            it.operation?.cancel()
        }
        return previous.handles.values.mapNotNull { it.value }
    }

    private data class Handle<T>(
        val ticket: Any,
        val acquisition: Job? = null,
        val value: T? = null,
        val operation: Job? = null,
    )

    private data class Snapshot<T>(
        val closed: Boolean = false,
        val handles: Map<String, Handle<T>> = emptyMap(),
    )
}
