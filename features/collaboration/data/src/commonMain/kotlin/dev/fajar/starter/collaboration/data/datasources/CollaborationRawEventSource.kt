package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.CollaborationSdkRawEventDto

/** Raw I/O through an existing client. The owner admits reads and retains the client lifetime. */
internal interface CollaborationRawEventSource {
    /** One exact event; never joins, starts sync, creates a timeline, retries or sends. */
    suspend fun read(roomId: String, eventId: String): CollaborationSdkRawEventDto
}
