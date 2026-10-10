package dev.fajar.starter.security.data.access.datasources

import dev.fajar.starter.security.data.access.dto.AccessResponse
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Single

/** Process-local, independently keyed records; a late response cannot replace another session. */
@Single
class MemoryAccessCache : AccessCache {
    private val records = MutableStateFlow<Map<String, AccessResponse>>(emptyMap())

    override fun observe(sessionId: String) = records.map { it[sessionId] }.distinctUntilChanged()

    override fun read(sessionId: String) = records.value[sessionId]

    override fun remove(sessionId: String) {
        records.update { it - sessionId }
    }

    override fun write(sessionId: String, response: AccessResponse) {
        records.update { current ->
            // A bounded insertion-order cache; eviction never reassigns a grant to another key.
            (current - sessionId + (sessionId to response)).entries.toList().takeLast(8).associate {
                it.toPair()
            }
        }
    }
}
