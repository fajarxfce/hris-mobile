package dev.fajar.starter.inbox.data.dto

import kotlinx.serialization.Serializable

@Serializable data class InboxActionReceiptDto(val id: String, val version: Long)
