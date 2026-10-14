package dev.fajar.starter.people.domain.replica.entities

import dev.fajar.starter.people.domain.entities.DirectoryEmployee

data class DirectoryRecord(val version: Long, val employee: DirectoryEmployee) {
    override fun toString() = "DirectoryRecord([redacted])"
}
