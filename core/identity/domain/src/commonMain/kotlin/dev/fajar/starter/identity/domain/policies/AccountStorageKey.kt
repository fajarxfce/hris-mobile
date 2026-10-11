package dev.fajar.starter.identity.domain.policies

import dev.fajar.starter.identity.domain.entities.Session

/** Stable across sign-ins; API audiences and accounts never share durable commands. */
fun accountStorageKey(session: Session): String =
    accountStorageKey(session.binding.audience, session.user.id)

fun accountStorageKey(audience: String, accountId: String): String =
    audience.length.toString() + ":" + audience + accountId.length + ":" + accountId
