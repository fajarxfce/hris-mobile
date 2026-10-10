package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class UserDto(val id: String, val name: String, val email: String) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && email.isNotBlank()) {
            "Invalid user response"
        }
    }
}
