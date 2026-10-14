package dev.fajar.starter.people.data.replica.dto

import kotlinx.serialization.Serializable

@Serializable
data class DirectoryReadAdmissionDto(val revision: String, val kind: String, val code: String?)
