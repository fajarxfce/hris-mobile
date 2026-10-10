package dev.fajar.starter.dashboard.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ActivityDto(val id: String, val title: String, val detail: String, val time: String)
