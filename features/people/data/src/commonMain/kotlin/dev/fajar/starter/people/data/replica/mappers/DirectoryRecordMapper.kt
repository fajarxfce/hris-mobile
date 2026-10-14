@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.people.data.replica.mappers

import dev.fajar.starter.people.data.replica.dto.DirectoryRecordDto
import dev.fajar.starter.people.domain.entities.DirectoryEmployee
import dev.fajar.starter.people.domain.replica.entities.DirectoryRecord
import kotlin.uuid.Uuid

fun DirectoryRecordDto.toRecord(expectedId: String): DirectoryRecord {
    require(id == expectedId && Uuid.parse(id).toString() == id)
    require(version in 0..9_007_199_254_740_991L)
    require(employeeNumber.isNotBlank() && employeeNumber.length <= 32)
    require(name.isNotBlank() && name.length <= 200)
    require((positionId == null) == (position == null))
    require((departmentId == null) == (department == null))
    for (identifier in listOfNotNull(positionId, departmentId)) require(
        Uuid.parse(identifier).toString() == identifier
    )
    for (label in listOfNotNull(position, department)) require(
        label.isNotBlank() && label.length <= 200
    )
    return DirectoryRecord(
        version,
        DirectoryEmployee(id, employeeNumber, name, position, department, positionId, departmentId),
    )
}

fun DirectoryRecord.toReplicaDto() =
    employee.let {
        DirectoryRecordDto(
            it.id,
            version,
            it.employeeNumber,
            it.name,
            it.positionId,
            it.position,
            it.departmentId,
            it.department,
        )
    }
