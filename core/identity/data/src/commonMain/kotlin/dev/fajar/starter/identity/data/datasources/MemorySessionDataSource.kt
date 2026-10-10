package dev.fajar.starter.identity.data.datasources

import dev.fajar.starter.identity.data.dto.SessionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.Single

@Single
class MemorySessionDataSource : SessionDataSource {
    private val current = MutableStateFlow<SessionDto?>(null)
    override val record = current.asStateFlow()

    override fun write(session: SessionDto?) {
        current.value = session
    }
}
