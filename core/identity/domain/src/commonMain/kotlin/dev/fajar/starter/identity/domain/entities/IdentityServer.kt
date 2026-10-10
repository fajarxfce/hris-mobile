package dev.fajar.starter.identity.domain.entities

/** Selected by the application environment, never by a restored credential or server response. */
data class IdentityServer(val audience: String, val deviceName: String)
