package dev.fajar.starter.inbox.data.push.mappers

import dev.fajar.starter.inbox.data.push.dto.*
import dev.fajar.starter.inbox.domain.push.entities.*
import dev.fajar.starter.inbox.domain.push.policies.canonicalInboxPushId
import kotlin.time.Instant

fun InboxPushQueueDto.toQueue(): InboxPushQueue {
    require(format == 1 && pending.size <= 64 && pending.size + attempts.size <= 512)
    val owner =
        if (audience == null && accountId == null) null
        else {
            require(!audience.isNullOrBlank() && audience.length <= 2048)
            require(accountId != null && canonicalInboxPushId(accountId))
            InboxPushOwner(audience, accountId)
        }
    require(owner != null || pending.isEmpty())
    val eventIds = pending.map { it.eventId }
    require(eventIds.all(::canonicalInboxPushId) && eventIds.distinct().size == eventIds.size)
    val attempted =
        attempts.map {
            require(
                canonicalInboxPushId(it.eventId) &&
                    canonicalInboxPushId(it.companyId) &&
                    canonicalInboxPushId(it.accountId)
            )
            require(it.audience.isNotBlank() && it.audience.length <= 2048)
            InboxPushAttempt(
                it.eventId,
                it.companyId,
                InboxPushOwner(it.audience, it.accountId),
                instant(it.attemptedAt),
            )
        }
    require(
        attempted.distinctBy { Triple(it.owner, it.companyId, it.eventId) }.size == attempted.size
    )
    require(
        pending.none { hint ->
            attempted.any {
                it.owner == owner && it.companyId == hint.companyId && it.eventId == hint.eventId
            }
        }
    )
    return InboxPushQueue(
        owner,
        pending.map {
            require(canonicalInboxPushId(it.companyId) && canonicalInboxPushId(it.inboxId))
            require(it.preparations in 0..3)
            QueuedInboxPush(
                it.eventId,
                it.companyId,
                it.inboxId,
                instant(it.receivedAt),
                it.preparations,
            )
        },
        attempted,
    )
}

private fun instant(value: String): Instant =
    Instant.parse(value).also { require(it.toEpochMilliseconds() in 1 until 253_402_300_800_000L) }

fun InboxPushQueue.toDto() =
    InboxPushQueueDto(
        audience = owner?.audience,
        accountId = owner?.accountId,
        pending =
            pending.map {
                QueuedInboxPushDto(
                    it.eventId,
                    it.companyId,
                    it.inboxId,
                    it.receivedAt.toString(),
                    it.preparations,
                )
            },
        attempts =
            attempts.map {
                InboxPushAttemptDto(
                    it.eventId,
                    it.companyId,
                    it.owner.audience,
                    it.owner.accountId,
                    it.attemptedAt.toString(),
                )
            },
    )
