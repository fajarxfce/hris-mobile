package dev.fajar.starter.identity.data.mappers

import dev.fajar.starter.identity.data.dto.UserDto
import dev.fajar.starter.identity.domain.entities.User

fun UserDto.toUser() = User(id = id, name = name, email = email)
