package dev.fajar.starter.workspace.data.dto

import kotlinx.serialization.Serializable

@Serializable data class CompanyAccessDto(val companyId: String, val permissions: List<String>)
