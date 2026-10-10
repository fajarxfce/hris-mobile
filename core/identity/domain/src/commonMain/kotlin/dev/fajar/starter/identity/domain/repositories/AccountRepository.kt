package dev.fajar.starter.identity.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.AccountSnapshot

interface AccountRepository {
    suspend fun load(sessionId: String): AppResult<AccountSnapshot>
}
