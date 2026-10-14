package dev.fajar.starter.people.domain.profile.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.profile.entities.*
import dev.fajar.starter.people.domain.profile.entities.SelfProfileAccess
import dev.fajar.starter.people.domain.profile.policies.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

val SELF_PROFILE_FRESHNESS_PERIOD = 1.hours

fun initialSelfProfileCheckpoint(access: SelfProfileAccess, generation: String, now: Instant) =
    SelfProfileCheckpoint(
        access,
        generation,
        0,
        SelfProfileSyncPhase.BOOTSTRAP,
        null,
        false,
        null,
        now + SELF_PROFILE_FRESHNESS_PERIOD,
        null,
    )

internal fun advanceSelfProfileCheckpoint(
    previous: SelfProfileCheckpoint,
    page: SelfProfileSyncPage,
    now: Instant,
): SelfProfileCheckpoint {
    val caughtUp =
        page.phase == SelfProfileSyncPhase.CHANGES && !page.hasMore && !page.pendingPublication
    return previous.copy(
        revision = previous.revision + 1,
        phase = page.phase,
        cursor = page.cursor,
        published = previous.published || caughtUp,
        refreshedAt = if (caughtUp) now else previous.refreshedAt,
        refreshAfter = if (caughtUp) now + SELF_PROFILE_FRESHNESS_PERIOD else previous.refreshAfter,
        pollAt = if (page.hasMore || caughtUp) null else now + page.pollAfterSeconds.seconds,
    )
}
