package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.SdkSyncBatch
import dev.fajar.starter.collaboration.data.dto.SdkSyncMembership
import dev.fajar.starter.collaboration.data.dto.SdkSyncRoomChange
import org.matrix.rustcomponents.sdk.SyncResponseV2
import org.matrix.rustcomponents.sdk.SyncTimelineMembershipV2

/** Copies the SDK's already bounded locators; no content, admission or cursor acquisition. */
internal fun SyncResponseV2.toSdkSyncBatch() =
    SdkSyncBatch(
        previousBatch,
        nextBatch,
        timelineChanges.rooms.map { room ->
            SdkSyncRoomChange(
                room.roomId,
                when (room.membership) {
                    SyncTimelineMembershipV2.JOINED -> SdkSyncMembership.Joined
                    SyncTimelineMembershipV2.LEFT -> SdkSyncMembership.Left
                },
                room.eventIds.toList(),
                room.limited,
            )
        },
        timelineChanges.rescanRequired,
    )
