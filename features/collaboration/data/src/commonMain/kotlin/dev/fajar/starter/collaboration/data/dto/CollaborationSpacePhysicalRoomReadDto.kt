package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable data class CollaborationSpacePhysicalRoomReadDto(val matrixRoomId: String)

@Serializable
data class CollaborationSpaceOriginalRoomReadDto(val companyId: String, val matrixRoomId: String)
