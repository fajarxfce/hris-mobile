package dev.fajar.starter.identity.data.datasources

import dev.fajar.starter.identity.data.dto.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.Single

/** Owns the process-local demo session. Passwords and tokens are never stored. */
@Single
class MemorySessionDataSource : SessionDataSource {
    private val currentUser = MutableStateFlow<UserDto?>(null)
    override val user = currentUser.asStateFlow()

    override fun write(user: UserDto?) {
        currentUser.value = user
    }
}
