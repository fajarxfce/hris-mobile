package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkRecord
import org.matrix.rustcomponents.sdk.Session

/**
 * A selected protected record and its fixed writer/session; the acquisition owns their lifetime.
 */
internal class AndroidSdkPreparedSession(
    val record: CollaborationSdkRecord,
    val persistence: SdkSessionPersistence,
    val session: Session,
) {
    override fun toString() = "AndroidSdkPreparedSession([redacted])"
}
