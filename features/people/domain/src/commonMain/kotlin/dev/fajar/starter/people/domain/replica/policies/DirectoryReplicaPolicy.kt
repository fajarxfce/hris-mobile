package dev.fajar.starter.people.domain.replica.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.replica.entities.*
import dev.fajar.starter.people.domain.replica.entities.DirectoryAccess
import dev.fajar.starter.people.domain.replica.policies.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

val DIRECTORY_FRESHNESS_PERIOD = 1.hours

fun initialDirectoryCheckpoint(access: DirectoryAccess, generation: String, now: Instant) =
    DirectoryCheckpoint(
        access,
        generation,
        0,
        DirectorySyncPhase.BOOTSTRAP,
        null,
        false,
        null,
        now + DIRECTORY_FRESHNESS_PERIOD,
        null,
    )

internal fun advanceDirectoryCheckpoint(
    previous: DirectoryCheckpoint,
    page: DirectorySyncPage,
    now: Instant,
): DirectoryCheckpoint {
    val caughtUp =
        page.phase == DirectorySyncPhase.CHANGES && !page.hasMore && !page.pendingPublication
    return previous.copy(
        revision = previous.revision + 1,
        phase = page.phase,
        cursor = page.cursor,
        published = previous.published || caughtUp,
        refreshedAt = if (caughtUp) now else previous.refreshedAt,
        refreshAfter = if (caughtUp) now + DIRECTORY_FRESHNESS_PERIOD else previous.refreshAfter,
        pollAt = if (page.hasMore || caughtUp) null else now + page.pollAfterSeconds.seconds,
    )
}
