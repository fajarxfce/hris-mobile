package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Validates exact public facts without turning age, local ownership or a room into permission. */
fun validCollaborationSpaceDirectTransport(value: CollaborationSpaceDirectTransport): Boolean {
    val conversation = value.conversation
    val owner = conversation.owner
    val device = value.device
    val room = value.room
    if (
        !validCollaborationSpaceConversation(conversation) ||
            value.nativeSessionVersion < 0 ||
            !validCollaborationSpaceDeviceStatus(device) ||
            !validCollaborationSpaceConversationId(device.id) ||
            device.state != CollaborationDeviceState.ACTIVE ||
            device.version !in 1..999_999 ||
            !validCollaborationSpacePublishedRoom(room, conversation.createdAt) ||
            room.publishedAt > device.observedAt
    )
        return false
    val gateway = device.gateway ?: return false
    if (
        !validCollaborationSpaceConversationId(gateway.id) ||
            gateway.revokedAt != null ||
            gateway.expiresAt <= device.observedAt
    )
        return false
    val account = owner.parent.accountId.replace("-", "")
    val logicalDevice = device.id.replace("-", "")
    val user = value.matrixUserId.substringBefore(':')
    if (
        value.matrixUserId.length > 255 ||
            value.matrixUserId.substringAfter(':', "") != room.serverName
    )
        return false
    val nil = "0".repeat(32)
    val legacyUser =
        user.matches(Regex("@hris_[a-f0-9]{32}_$account")) && !user.startsWith("@hris_${nil}_")
    val spaceUser = user == "@hrisg_${owner.spaceId.replace("-", "")}_$account"
    if (!legacyUser && !spaceUser) return false
    val originalCompany = room.original?.position?.companyId?.replace("-", "")
    if (originalCompany != null && user != "@hris_${originalCompany}_$account") return false
    // A newly enrolled SPACE device may retain a selected LEGACY user and original room.
    // Device origin describes the crypto store, not the age or namespace of that user.
    return when (device.origin) {
        CollaborationSpaceDeviceOrigin.SPACE -> value.matrixDeviceId == "HRISG_$logicalDevice"
        CollaborationSpaceDeviceOrigin.LEGACY ->
            legacyUser &&
                value.matrixDeviceId.matches(Regex("HRIS_[a-f0-9]{32}")) &&
                value.matrixDeviceId != "HRIS_$nil"
    }
}
