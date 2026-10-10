package dev.fajar.starter.identity.data.datasources

import dev.fajar.starter.identity.data.dto.UserDto
import kotlinx.coroutines.flow.StateFlow

interface SessionDataSource {
    val user: StateFlow<UserDto?>

    fun write(user: UserDto?)
}
