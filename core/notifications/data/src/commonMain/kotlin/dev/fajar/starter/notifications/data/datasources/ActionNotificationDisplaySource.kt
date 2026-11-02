package dev.fajar.starter.notifications.data.datasources

import dev.fajar.starter.notifications.data.dto.*

/**
 * Raw native IPC, with no history, company, session or envelope admission. The application must
 * serialize all operations, validate the exact render immediately before submission, and cancel and
 * join obsolete work before cleanup. OS IPC cannot be atomic with application storage.
 */
interface ActionNotificationDisplaySource {
    suspend fun show(payload: ActionNotificationPayload): ActionNotificationSubmission

    /** Opaque current references only; never exposes the OS's retained preview or reply text. */
    suspend fun active(): List<ActionNotificationReference>

    /** Must not remove a newer render using the same stable notification ID. */
    suspend fun remove(expected: ActionNotificationReference): ActionNotificationRemoval

    /** Only this source's namespace. Does not require decrypting the previous account's data. */
    suspend fun clear()
}
