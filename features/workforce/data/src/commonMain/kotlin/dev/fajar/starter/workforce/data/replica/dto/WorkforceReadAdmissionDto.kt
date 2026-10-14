package dev.fajar.starter.workforce.data.replica.dto

import kotlinx.serialization.Serializable

@Serializable
data class WorkforceReadAdmissionDto(val revision: String, val kind: String, val code: String?)
