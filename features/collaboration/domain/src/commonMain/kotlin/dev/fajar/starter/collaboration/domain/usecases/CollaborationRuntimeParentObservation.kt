package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.workspace.domain.entities.Workspace
import kotlin.time.Instant
import kotlin.time.TimeMark

/** One collector retains one parent snapshot; no company cache or remote authority is created. */
internal class CollaborationRuntimeParentObservation(
    session: Session,
    wallTime: Instant,
    private val started: TimeMark,
) {
    private val sessionId = session.id
    private val audience = session.binding.audience
    private val accountId = session.user.id
    private val nativeSessionId = session.binding.serverId
    private var baseline = wallTime
    var expiresAt = Instant.fromEpochMilliseconds(session.binding.expiresAtEpochMillis)
        private set

    var selected: AppResult<Workspace?>? = null

    fun matches(session: Session) =
        session.id == sessionId &&
            session.binding.audience == audience &&
            session.user.id == accountId &&
            session.binding.serverId == nativeSessionId

    /** A clock rollback or same-family refresh cannot resurrect an elapsed admission. */
    fun observe(session: Session, wallTime: Instant): Instant {
        check(matches(session))
        expiresAt =
            minOf(expiresAt, Instant.fromEpochMilliseconds(session.binding.expiresAtEpochMillis))
        val elapsed = started.elapsedNow()
        baseline = maxOf(baseline, wallTime - elapsed)
        return baseline + elapsed
    }
}
