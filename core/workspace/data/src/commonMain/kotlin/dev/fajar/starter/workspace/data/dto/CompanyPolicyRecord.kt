package dev.fajar.starter.workspace.data.dto

import kotlinx.serialization.Serializable

@Serializable data class CompanyPolicyRecord(val policy: CompanyPolicyDto, val requestedAt: String)
