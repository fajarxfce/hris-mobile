package dev.fajar.starter.notifications.data.datasources

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.notifications.data.mappers.*
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Raw adapter. A successful Binder submission is not user visibility, reading or delivery. */
class AndroidActionNotificationDisplaySource(
    context: Context,
    private val namespace: String,
    private val iconResource: Int,
    private val intents: AndroidActionNotificationIntents,
    private val labels: () -> ActionNotificationLabels,
) : ActionNotificationDisplaySource {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)
    private val channel = "owned-$namespace"
    private val prefix = "owned:$namespace:"
    private val pendingFile =
        AtomicFile(File(this.context.noBackupFilesDir, "owned-notification-$namespace.json"))

    init {
        require(namespace.matches(Regex("[a-z][a-z0-9_-]{0,47}")))
    }

    override suspend fun show(payload: ActionNotificationPayload): ActionNotificationSubmission =
        withContext(Dispatchers.IO) { submit(payload) }

    private suspend fun submit(payload: ActionNotificationPayload): ActionNotificationSubmission {
        currentCoroutineContext().ensureActive()
        require(validActionNotificationPayload(payload))
        if (!allowed()) return ActionNotificationSubmission.PermissionRequired
        val copy = labels()
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(channel, copy.channel, NotificationManager.IMPORTANCE_DEFAULT)
            )
            if (
                manager.getNotificationChannel(channel)?.importance ==
                    NotificationManager.IMPORTANCE_NONE
            )
                return ActionNotificationSubmission.ChannelDisabled
        }
        val tag = prefix + payload.reference.id
        val active = manager.activeNotifications
        // Android's per-package limit also includes the application's other notification types.
        if (active.size >= 50 && active.none { it.tag == tag && it.id == 0 })
            return ActionNotificationSubmission.CapacityReached
        val pending = readPending()
        if (
            pending.size >= ACTION_NOTIFICATION_PENDING_CAPACITY &&
                pending.none { it.id == payload.reference.id }
        )
            return ActionNotificationSubmission.CapacityReached
        val replay =
            active.any {
                it.tag == tag &&
                    it.id == 0 &&
                    it.notification.extras.getString(RENDER) == payload.reference.renderId
            }
        val notification = build(payload, copy, replay)
        currentCoroutineContext().ensureActive()
        // Keep an opaque ID before external IPC, including requests not yet reported active.
        writePending(pending.filterNot { it.id == payload.reference.id } + payload.reference)
        currentCoroutineContext().ensureActive()
        manager.notify(tag, 0, notification)
        // Cancellation after notify withholds success; the owner must reconcile/clean exact
        // metadata.
        currentCoroutineContext().ensureActive()
        return ActionNotificationSubmission.Submitted
    }

    override suspend fun active(): List<ActionNotificationReference> =
        withContext(Dispatchers.IO) { readActive() }

    private suspend fun readActive(): List<ActionNotificationReference> {
        currentCoroutineContext().ensureActive()
        val references =
            manager.activeNotifications.mapNotNull { entry ->
                if (entry.id != 0 || entry.tag?.startsWith(prefix) != true) return@mapNotNull null
                val render = entry.notification.extras.getString(RENDER) ?: return@mapNotNull null
                ActionNotificationReference(entry.tag.removePrefix(prefix), render)
                    .takeIf(::validActionNotificationReference)
            }
        currentCoroutineContext().ensureActive()
        return references
    }

    override suspend fun remove(expected: ActionNotificationReference): ActionNotificationRemoval =
        withContext(Dispatchers.IO) { cancel(expected) }

    private suspend fun cancel(expected: ActionNotificationReference): ActionNotificationRemoval {
        currentCoroutineContext().ensureActive()
        require(validActionNotificationReference(expected))
        val pending = readPending()
        val latest = pending.singleOrNull { it.id == expected.id }
        if (latest != null && latest != expected) return ActionNotificationRemoval.Replaced
        val tag = prefix + expected.id
        val current = manager.activeNotifications.singleOrNull { it.tag == tag && it.id == 0 }
        if (
            latest == null &&
                current != null &&
                current.notification.extras.getString(RENDER) != expected.renderId
        )
            return ActionNotificationRemoval.Replaced
        // Under the required single writer, latest==expected also fences an older still-visible
        // card while this newer request waits in the OS queue. An absent card still needs cancel.
        currentCoroutineContext().ensureActive()
        manager.cancel(tag, 0)
        currentCoroutineContext().ensureActive()
        if (latest != null) writePending(pending.filterNot { it.id == expected.id })
        currentCoroutineContext().ensureActive()
        return if (current == null) ActionNotificationRemoval.Absent
        else ActionNotificationRemoval.Removed
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { cancelNamespace() }

    private suspend fun cancelNamespace() {
        currentCoroutineContext().ensureActive()
        var indexFailure: Exception? = null
        val pending =
            try {
                readPending()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                indexFailure = failure
                emptyList()
            }
        val tags = pending.map { prefix + it.id }.toMutableSet()
        for (entry in manager.activeNotifications) {
            if (entry.id == 0 && entry.tag?.startsWith(prefix) == true) tags += entry.tag
        }
        for (tag in tags) {
            currentCoroutineContext().ensureActive()
            manager.cancel(tag, 0)
        }
        // Corrupt inventory must not silently authorize more posts; visible cleanup is best effort.
        indexFailure?.let { throw it }
        writePending(emptyList())
        currentCoroutineContext().ensureActive()
    }

    private fun readPending(): List<ActionNotificationReference> {
        val input =
            try {
                pendingFile.openRead()
            } catch (missing: FileNotFoundException) {
                if (
                    pendingFile.baseFile.exists() ||
                        File(pendingFile.baseFile.path + ".bak").exists()
                )
                    throw missing
                return emptyList()
            }
        val buffer = ByteArray(ACTION_NOTIFICATION_PENDING_BYTES + 1)
        try {
            var size = 0
            input.use {
                while (size < buffer.size) {
                    val read = it.read(buffer, size, buffer.size - size)
                    if (read < 0) break
                    check(read > 0)
                    size += read
                }
            }
            val bytes = buffer.copyOf(size)
            try {
                return decodeActionNotificationPendingIndex(bytes)
            } finally {
                bytes.fill(0)
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun writePending(references: List<ActionNotificationReference>) {
        val bytes = encodeActionNotificationPendingIndex(references)
        var output: FileOutputStream? = null
        try {
            output = pendingFile.startWrite()
            output.write(bytes)
            output.fd.sync()
            pendingFile.finishWrite(output)
            output = null
            // AtomicFile can log rename failures; verify the committed inventory before IPC.
            check(readPending() == references.sortedBy { it.id })
        } catch (failure: Throwable) {
            output?.let { stream -> runCatching { pendingFile.failWrite(stream) } }
            throw failure
        } finally {
            bytes.fill(0)
        }
    }

    private fun allowed() =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun build(
        payload: ActionNotificationPayload,
        copy: ActionNotificationLabels,
        replay: Boolean,
    ): Notification {
        fun pending(kind: ActionNotificationKind) =
            intents.pending(payload.reference, kind, payload.envelopes.getValue(kind))
        val reply =
            NotificationCompat.Action.Builder(0, copy.reply, pending(ActionNotificationKind.Reply))
                .addRemoteInput(
                    RemoteInput.Builder(AndroidActionNotificationIntents.REPLY_TEXT)
                        .setLabel(copy.reply)
                        .setAllowFreeFormInput(true)
                        .build()
                )
                .setAllowGeneratedReplies(false)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .build()
        val public =
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(iconResource)
                .setContentTitle(copy.privateTitle)
                .setContentText(copy.privateBody)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(iconResource)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setContentIntent(pending(ActionNotificationKind.Open))
            .setDeleteIntent(pending(ActionNotificationKind.Dismiss))
            .addAction(reply)
            .addAction(
                NotificationCompat.Action.Builder(
                        0,
                        copy.markRead,
                        pending(ActionNotificationKind.MarkRead),
                    )
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                        0,
                        copy.mute,
                        pending(ActionNotificationKind.Mute),
                    )
                    .build()
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAllowSystemGeneratedContextualActions(false)
            .setOnlyAlertOnce(replay)
            .setAutoCancel(false)
            .setSilent(payload.silent)
            .addExtras(Bundle().apply { putString(RENDER, payload.reference.renderId) })
            .build()
    }

    companion object {
        private const val RENDER = "owned_notification_render"
    }
}
