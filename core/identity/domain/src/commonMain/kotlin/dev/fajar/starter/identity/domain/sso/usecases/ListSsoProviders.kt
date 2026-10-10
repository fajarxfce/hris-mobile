package dev.fajar.starter.identity.domain.sso.usecases

import dev.fajar.starter.identity.domain.sso.repositories.SsoRepository

class ListSsoProviders(private val sso: SsoRepository) {
    suspend operator fun invoke() = sso.providers()
}
