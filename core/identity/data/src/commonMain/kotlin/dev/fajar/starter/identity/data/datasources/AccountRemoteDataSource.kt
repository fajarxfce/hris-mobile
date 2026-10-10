package dev.fajar.starter.identity.data.datasources

import dev.fajar.starter.identity.data.dto.AccountSnapshotDto

interface AccountRemoteDataSource {
    suspend fun current(sessionId: String): AccountSnapshotDto
}
