package dev.fajar.starter.identity.domain.entities

import dev.fajar.starter.common.result.Failure

/** Local credentials are gone. A failed remote attempt does not imply server revocation. */
data class SignOutReceipt(val remoteRevoked: Boolean, val remoteFailure: Failure? = null)
