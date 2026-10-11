package dev.fajar.starter.identity.domain.policies

import dev.fajar.starter.identity.domain.entities.Session

/** Stable across sign-ins; API audiences and accounts never share durable commands. */
fun accountStorageKey(session: Session): String {
    val audience = session.binding.audience
    val account = session.user.id
    return audience.length.toString() + ":" + audience + account.length + ":" + account
}
