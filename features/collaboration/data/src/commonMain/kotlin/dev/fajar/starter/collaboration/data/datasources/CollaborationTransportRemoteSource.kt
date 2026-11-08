package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.CollaborationDirectTransportDto

fun interface CollaborationTransportRemoteSource {
    suspend fun transport(
        session: String,
        company: String,
        conversation: String,
    ): CollaborationDirectTransportDto
}
