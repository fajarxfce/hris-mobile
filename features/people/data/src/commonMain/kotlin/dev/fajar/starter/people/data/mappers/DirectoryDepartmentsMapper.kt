@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.people.data.mappers

import dev.fajar.starter.people.data.dto.DirectoryDepartmentsDto
import dev.fajar.starter.people.domain.entities.DirectoryDepartment
import dev.fajar.starter.people.domain.entities.DirectoryDepartments
import kotlin.uuid.Uuid

fun DirectoryDepartmentsDto.toDepartments(): DirectoryDepartments {
    require(items.size <= 50 && (!hasMore || items.size == 50))
    require(items.distinctBy { it.id }.size == items.size)
    return DirectoryDepartments(
        items.map {
            require(Uuid.parse(it.id).toString() == it.id)
            require(it.name.isNotBlank() && it.name.length <= 200)
            DirectoryDepartment(it.id, it.name)
        },
        hasMore,
    )
}
