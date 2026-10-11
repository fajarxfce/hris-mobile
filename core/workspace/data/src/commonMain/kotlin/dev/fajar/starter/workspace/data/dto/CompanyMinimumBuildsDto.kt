package dev.fajar.starter.workspace.data.dto

import kotlinx.serialization.Serializable

@Serializable data class CompanyMinimumBuildsDto(val android: Long, val ios: Long, val web: Long)
