package dev.fajar.starter.dashboard.data.dto

import kotlinx.serialization.Serializable

@Serializable data class ActivityPreferenceRequest(val activityId: String, val saved: Boolean)
