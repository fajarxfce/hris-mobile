package dev.fajar.starter.inbox.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class InboxReadAdmissionDto(val revision: String, val kind: String, val code: String?)
