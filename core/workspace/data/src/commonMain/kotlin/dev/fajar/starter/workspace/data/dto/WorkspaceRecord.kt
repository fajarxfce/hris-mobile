package dev.fajar.starter.workspace.data.dto

data class WorkspaceRecord(
    val id: String,
    val sessionId: String,
    val audience: String,
    val companyId: String,
    val companyName: String,
    val companyCode: String,
    val timezone: String,
    val permissions: Set<String>,
)
