package dev.fajar.starter.inbox.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class InboxCommandDto(
    val id: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val itemId: String,
    val expectedVersion: Long,
    val action: String,
    val createdAt: String,
)
