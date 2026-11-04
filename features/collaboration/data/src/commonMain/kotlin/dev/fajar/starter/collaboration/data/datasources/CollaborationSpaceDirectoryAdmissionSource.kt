package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.replica.SpaceDirectoryAdmissionDto
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

/** Process-owned pending writes, separate from company-chat admission buffers. */
interface CollaborationSpaceDirectoryAdmissionSource {
    fun observe(key: String): Flow<SpaceDirectoryAdmissionDto?>

    fun current(key: String): SpaceDirectoryAdmissionDto?

    fun write(key: String, value: SpaceDirectoryAdmissionDto?)

    /** A competing buffer mutation rejects this clear; never delete a newer denial. */
    fun clearIfCurrent(key: String, expected: SpaceDirectoryAdmissionDto?): Boolean
}

@Single
class MemoryCollaborationSpaceDirectoryAdmissionSource :
    CollaborationSpaceDirectoryAdmissionSource {
    private data class Buffer(
        val values: Map<String, SpaceDirectoryAdmissionDto> = emptyMap(),
        val overflow: Boolean = false,
    )

    private val buffer = MutableStateFlow(Buffer())

    override fun observe(key: String) =
        buffer
            .map {
                check(!it.overflow)
                it.values[key]
            }
            .distinctUntilChanged()

    override fun current(key: String): SpaceDirectoryAdmissionDto? =
        buffer.value.let {
            check(!it.overflow)
            it.values[key]
        }

    override fun write(key: String, value: SpaceDirectoryAdmissionDto?) {
        require(key.length in 1..4096)
        buffer.update { previous ->
            when {
                previous.overflow -> previous
                value == null -> previous.copy(values = previous.values - key)
                key !in previous.values && previous.values.size >= 128 ->
                    previous.copy(overflow = true)
                else -> previous.copy(values = previous.values + (key to value))
            }
        }
        check(!buffer.value.overflow)
    }

    override fun clearIfCurrent(key: String, expected: SpaceDirectoryAdmissionDto?): Boolean {
        require(key.length in 1..4096)
        val previous = buffer.value
        check(!previous.overflow)
        if (previous.values[key] != expected) return false
        return buffer.compareAndSet(previous, previous.copy(values = previous.values - key))
    }
}
