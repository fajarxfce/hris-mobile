package dev.fajar.starter.identity.domain.sso.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.sso.entities.*

interface SsoRepository {
    suspend fun providers(): AppResult<List<SsoProvider>>

    suspend fun authorize(provider: SsoProvider): AppResult<SsoProof>
}
