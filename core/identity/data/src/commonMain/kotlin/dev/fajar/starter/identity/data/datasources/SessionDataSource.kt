package dev.fajar.starter.identity.data.datasources

import dev.fajar.starter.identity.data.dto.SessionDto
import kotlinx.coroutines.flow.StateFlow

/** In-process DTO cache. It neither persists credentials nor decides session transitions. */
interface SessionDataSource {
    val record: StateFlow<SessionDto?>

    fun write(session: SessionDto?)
}
