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
        now + 15.minutes,
        null,
    )

/** Freshness advances only after catch-up; it never expires access to a published replica. */
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
        refreshAfter = if (caughtUp) now + 1.hours else previous.refreshAfter,
        pollAt = if (page.hasMore) null else now + page.pollAfterSeconds.seconds,
    )
}
