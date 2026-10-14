package dev.fajar.starter.people.data.profile.dto

import kotlinx.serialization.Serializable

@Serializable
data class SelfProfileReadAdmissionDto(val revision: String, val kind: String, val code: String?)
