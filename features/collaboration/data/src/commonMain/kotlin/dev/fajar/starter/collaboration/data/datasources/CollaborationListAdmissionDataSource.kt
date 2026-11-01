package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.replica.CollaborationListAdmissionDto
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

/** Process-owned pending writes; no acquisition, retry or permission decisions. */
interface CollaborationListAdmissionDataSource {
    fun observe(key: String): Flow<CollaborationListAdmissionDto?>

    fun current(key: String): CollaborationListAdmissionDto?

    fun write(key: String, value: CollaborationListAdmissionDto?)
}

@Single
class MemoryCollaborationListAdmissionDataSource : CollaborationListAdmissionDataSource {
    private data class Buffer(
        val records: Map<String, CollaborationListAdmissionDto> = emptyMap(),
        val overflow: Boolean = false,
    )

    private val buffer = MutableStateFlow(Buffer())

    override fun observe(key: String) =
        buffer
            .map {
                check(!it.overflow)
                it.records[key]
            }
            .distinctUntilChanged()

    override fun current(key: String): CollaborationListAdmissionDto? {
        val value = buffer.value
        check(!value.overflow)
        return value.records[key]
    }

    override fun write(key: String, value: CollaborationListAdmissionDto?) {
        buffer.update { current ->
            if (current.overflow) current
            else if (value == null) current.copy(records = current.records - key)
            else if (key !in current.records && current.records.size >= 128)
                current.copy(overflow = true)
            else current.copy(records = current.records + (key to value))
        }
        // Fail closed on exhausted pending-write capacity until this process is replaced.
        check(!buffer.value.overflow)
    }
}
