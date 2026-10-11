package dev.fajar.starter.inbox.domain.policies

import dev.fajar.starter.inbox.domain.entities.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

fun initialInboxCheckpoint(access: InboxAccess, generation: String, now: Instant) =
    InboxCheckpoint(
        access,
        generation,
        0,
        InboxSyncPhase.BOOTSTRAP,
        null,
        false,
        null,
        minOf(access.sessionExpiresAt, now + 15.minutes),
        null,
    )

/** Partial progress never renews offline access or publishes an incomplete replacement. */
internal fun advanceInboxCheckpoint(
    previous: InboxCheckpoint,
    page: InboxSyncPage,
    now: Instant,
): InboxCheckpoint {
    val caughtUp = page.phase == InboxSyncPhase.CHANGES && !page.hasMore && !page.pendingPublication
    return previous.copy(
        revision = previous.revision + 1,
        phase = page.phase,
        cursor = page.cursor,
        published = previous.published || caughtUp,
        refreshedAt = if (caughtUp) now else previous.refreshedAt,
        expiresAt =
            if (caughtUp) minOf(previous.access.sessionExpiresAt, now + 1.hours)
            else previous.expiresAt,
        pollAt = if (page.hasMore) null else now + page.pollAfterSeconds.seconds,
    )
}
