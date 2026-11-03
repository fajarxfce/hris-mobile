package dev.fajar.hris.collaboration.proof

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.notifications.data.dto.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real NotificationManager/PendingIntent/RemoteInput proof; no provider or real employee account.
 */
internal class NativeNotificationProbe(private val context: Context, private val directory: File) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val namespace = "chat-proof"
    private val prefix = "owned:$namespace:"
    private val activity = ComponentName(context, HrisScheduledProofActivity::class.java)
    private val receiver = ComponentName(context, NotificationProofReceiver::class.java)
    private val intents = AndroidActionNotificationIntents(context, namespace, activity, receiver)
    private val labels =
        ActionNotificationLabels(
            "Chat proof",
            "HRIS",
            "New message",
            "Reply",
            "Mark as read",
            "Mute",
        )
    private val source =
        AndroidActionNotificationDisplaySource(
            context,
            namespace,
            android.R.drawable.ic_dialog_info,
            intents,
        ) {
            labels
        }
    private val cases = mutableListOf<String>()

    suspend fun run(denied: Boolean) {
        try {
            source.clear()
            if (denied) {
                check(source.show(payload()) == ActionNotificationSubmission.PermissionRequired)
                check(source.active().isEmpty())
                cases += "permission-denial-does-not-submit"
            } else {
                replacements()
                actions()
                boundedInput()
                cleanup()
                disabledChannel()
                inventoryReopen()
                olderVisibleRender()
                inventoryCapacity()
                corruptInventory()
            }
        } finally {
            source.clear()
            manager.cancel("unrelated-proof", 71)
            while (NotificationProofReceiver.received.tryReceive().isSuccess) {
                /* Release fixture inputs. */
            }
        }
        waitFor { source.active().isEmpty() }
        directory.mkdirs()
        File(directory, "native-notification.json")
            .writeText(
                JSONObject()
                    .put("complete", true)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("cases", JSONArray(cases))
                    .put("cleanedUp", true)
                    .put(
                        "scope",
                        "Raw Android IPC only; no HRIS admission, durable reply, OS-death replay or application wiring",
                    )
                    .toString()
            )
    }

    private suspend fun replacements() {
        val first = payload()
        check(source.show(first) == ActionNotificationSubmission.Submitted)
        waitFor { source.active() == listOf(first.reference) }
        val shown = current(first.reference.id)
        check(shown.visibility == Notification.VISIBILITY_PRIVATE)
        check(
            shown.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString() ==
                "HRIS"
        )
        check(
            shown.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString() ==
                "New message"
        )
        check(shown.publicVersion.actions.isNullOrEmpty())
        check(shown.publicVersion.contentIntent == null)
        check(shown.actions.size == 3)
        if (Build.VERSION.SDK_INT >= 31) {
            check(shown.contentIntent.isActivity)
            check(shown.contentIntent.isImmutable && shown.deleteIntent.isImmutable)
            check(!shown.actions[0].actionIntent.isImmutable)
            check(shown.actions.drop(1).all { it.actionIntent.isImmutable })
        }
        cases += "actual-private-display-and-immutable-public-version"
        check(source.show(first) == ActionNotificationSubmission.Submitted)
        waitFor { current(first.reference.id).flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 }
        cases += "same-render-replay-updates-stable-card"
        val replacement =
            first.copy(reference = first.reference.copy(renderId = "new-render"), silent = false)
        check(source.show(replacement) == ActionNotificationSubmission.Submitted)
        waitFor { source.active() == listOf(replacement.reference) }
        check(current(first.reference.id).flags and Notification.FLAG_ONLY_ALERT_ONCE == 0)
        check(source.remove(first.reference) == ActionNotificationRemoval.Replaced)
        check(source.active() == listOf(replacement.reference))
        check(source.remove(replacement.reference) == ActionNotificationRemoval.Removed)
        waitFor { source.active().isEmpty() }
        check(source.remove(first.reference) == ActionNotificationRemoval.Absent)
        cases += "stale-removal-preserves-new-render-and-new-render-may-alert"
    }

    private suspend fun actions() {
        val first = payload().let { it.copy(reference = it.reference.copy(id = "action-card")) }
        val second = first.reference.copy(renderId = "replacement")
        val oldReply =
            intents.pending(first.reference, ActionNotificationKind.Reply, "old-envelope")
        val reissued =
            intents.pending(first.reference, ActionNotificationKind.Reply, "changed-envelope")
        check(oldReply == reissued)
        val newReply = intents.pending(second, ActionNotificationKind.Reply, "new-envelope")
        check(oldReply != newReply)
        val input = Intent()
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(AndroidActionNotificationIntents.REPLY_TEXT).build()),
            input,
            Bundle().apply {
                putCharSequence(AndroidActionNotificationIntents.REPLY_TEXT, "Fictional reply")
            },
        )
        oldReply.send(context, 0, input)
        val old = receive()
        check(
            old.reference == first.reference &&
                old.envelope == "old-envelope" &&
                old.replyText == "Fictional reply"
        )
        cases += "same-render-reissue-keeps-original-immutable-envelope"
        newReply.send(context, 0, input)
        check(receive().let { it.reference == second && it.envelope == "new-envelope" })
        cases += "real-remote-input-and-old-render-envelope-remain-distinct"
        for (kind in
            listOf(
                ActionNotificationKind.MarkRead,
                ActionNotificationKind.Mute,
                ActionNotificationKind.Dismiss,
            )) {
            intents.pending(first.reference, kind, "${kind.key}-envelope").send(context, 0, input)
            val result = receive()
            check(
                result.kind == kind &&
                    result.replyText == null &&
                    result.envelope == "${kind.key}-envelope"
            )
        }
        cases += "immutable-actions-ignore-injected-remote-input"
        check("Aa".hashCode() == "BB".hashCode())
        val references = listOf("Aa", "BB").map { ActionNotificationReference(it, "render") }
        val pending =
            references.map {
                intents.pending(it, ActionNotificationKind.Dismiss, "${it.id}-envelope")
            }
        check(pending[0] != pending[1])
        pending.forEachIndexed { index, value ->
            value.send()
            check(receive().reference == references[index])
        }
        cases += "colliding-java-hashes-cannot-retarget-pending-intents"
    }

    private suspend fun boundedInput() {
        intents
            .pending(payload().reference, ActionNotificationKind.Reply, "bounded-envelope")
            .send()
        val absent = withTimeout(5_000) { NotificationProofReceiver.received.receive() }
        check(intents.input(absent) == null)
        for (value in listOf("", "x".repeat(16_385))) {
            val changed = Intent(absent)
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(AndroidActionNotificationIntents.REPLY_TEXT).build()),
                changed,
                Bundle().apply {
                    putCharSequence(AndroidActionNotificationIntents.REPLY_TEXT, value)
                },
            )
            check(intents.input(changed) == null)
        }
        check(intents.input(Intent(absent).setPackage("foreign.package")) == null)
        check(intents.input(Intent(absent).setComponent(activity)) == null)
        check(
            intents.input(
                Intent(absent)
                    .setData(absent.data!!.buildUpon().appendQueryParameter("x", "y").build())
            ) == null
        )
        cases += "missing-oversized-or-cross-component-input-is-rejected"
    }

    private suspend fun cleanup() {
        val other =
            NotificationCompat.Builder(context, "owned-$namespace")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Unrelated fictional update")
                .build()
        manager.notify("unrelated-proof", 71, other)
        check(source.show(payload()) == ActionNotificationSubmission.Submitted)
        waitFor {
            source.active().isNotEmpty() &&
                manager.activeNotifications.any { it.tag == "unrelated-proof" }
        }
        source.clear()
        waitFor { source.active().isEmpty() }
        check(manager.activeNotifications.any { it.tag == "unrelated-proof" && it.id == 71 })
        cases += "namespace-cleanup-preserves-unrelated-application-notifications"
    }

    private suspend fun disabledChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        manager.createNotificationChannel(
            NotificationChannel(
                "owned-chat-proof-blocked",
                "Disabled proof",
                NotificationManager.IMPORTANCE_NONE,
            )
        )
        val blocked =
            AndroidActionNotificationDisplaySource(
                context,
                "chat-proof-blocked",
                android.R.drawable.ic_dialog_info,
                AndroidActionNotificationIntents(context, "chat-proof-blocked", activity, receiver),
            ) {
                labels
            }
        check(blocked.show(payload()) == ActionNotificationSubmission.ChannelDisabled)
        check(blocked.active().isEmpty())
        cases += "disabled-channel-is-distinct-from-submitted"
    }

    private suspend fun receive(): ActionNotificationInput =
        requireNotNull(
            intents.input(withTimeout(5_000) { NotificationProofReceiver.received.receive() })
        )

    private val inventoryFile: File
        get() = File(context.noBackupFilesDir, "owned-notification-$namespace.json")

    private fun reopenedSource() =
        AndroidActionNotificationDisplaySource(
            context,
            namespace,
            android.R.drawable.ic_dialog_info,
            AndroidActionNotificationIntents(context, namespace, activity, receiver),
        ) {
            labels
        }

    private suspend fun inventoryReopen() {
        source.clear()
        val first = payload()
        check(source.show(first) == ActionNotificationSubmission.Submitted)
        waitFor { source.active() == listOf(first.reference) }
        val encoded = inventoryFile.readText()
        val index = JSONObject(encoded)
        check(index.getInt("schema") == 1 && index.length() == 2)
        val entry = index.getJSONArray("references").getJSONObject(0)
        check(
            entry.length() == 2 &&
                entry.getString("id") == first.reference.id &&
                entry.getString("renderId") == first.reference.renderId
        )
        check(!encoded.contains(first.title) && !encoded.contains(first.body))
        check(first.envelopes.values.none { encoded.contains(it) })
        val reopened = reopenedSource()
        val newer = first.copy(reference = first.reference.copy(renderId = "inventory-new"))
        check(reopened.show(newer) == ActionNotificationSubmission.Submitted)
        waitFor { reopened.active() == listOf(newer.reference) }
        // Create observable absence while preserving the real, previously committed inventory.
        // This is not a paused NotificationManager service or OS-queue fault injection.
        manager.cancel(prefix + newer.reference.id, 0)
        waitFor { reopened.active().isEmpty() }
        check(reopened.remove(first.reference) == ActionNotificationRemoval.Replaced)
        check(
            JSONObject(inventoryFile.readText())
                .getJSONArray("references")
                .getJSONObject(0)
                .getString("renderId") == newer.reference.renderId
        )
        cases += "newer-inventory-fences-stale-removal-while-os-card-is-absent"
        reopenedSource().clear()
        check(JSONObject(inventoryFile.readText()).getJSONArray("references").length() == 0)
        cases += "opaque-no-backup-inventory-reopens-and-clears-without-account-data"
    }

    private suspend fun olderVisibleRender() {
        source.clear()
        val first = payload()
        check(source.show(first) == ActionNotificationSubmission.Submitted)
        waitFor { source.active() == listOf(first.reference) }
        val older = current(first.reference.id)
        val newer = first.copy(reference = first.reference.copy(renderId = "latest-owned-render"))
        check(source.show(newer) == ActionNotificationSubmission.Submitted)
        waitFor { source.active() == listOf(newer.reference) }
        // Re-present a captured predecessor to exercise the pending-latest/older-visible state.
        manager.notify(prefix + first.reference.id, 0, older)
        waitFor { source.active() == listOf(first.reference) }
        check(reopenedSource().remove(newer.reference) == ActionNotificationRemoval.Removed)
        waitFor { source.active().isEmpty() }
        check(JSONObject(inventoryFile.readText()).getJSONArray("references").length() == 0)
        cases += "latest-owned-inventory-can-cancel-its-older-visible-predecessor"
    }

    private suspend fun inventoryCapacity() {
        source.clear()
        val original = inventoryFile.readBytes()
        try {
            val references = JSONArray()
            for (index in 0 until 64) references.put(
                JSONObject()
                    .put("id", "capacity-${index.toString().padStart(2, '0')}")
                    .put("renderId", "fixture")
            )
            val encoded = JSONObject().put("schema", 1).put("references", references).toString()
            inventoryFile.writeText(encoded)
            check(source.show(payload()) == ActionNotificationSubmission.CapacityReached)
            check(inventoryFile.readText() == encoded && source.active().isEmpty())
            cases += "full-opaque-inventory-does-not-evict-or-submit"
        } finally {
            inventoryFile.writeBytes(original)
        }
    }

    private suspend fun corruptInventory() {
        source.clear()
        check(source.show(payload()) == ActionNotificationSubmission.Submitted)
        waitFor { source.active().isNotEmpty() }
        val original = inventoryFile.readBytes()
        try {
            inventoryFile.writeText("{\"schema\":2,\"references\":[]}")
            check(
                runCatching {
                        source.show(
                            payload()
                                .copy(
                                    reference =
                                        ActionNotificationReference("new-card", "new-render")
                                )
                        )
                    }
                    .isFailure
            )
            check(runCatching { source.clear() }.isFailure)
            waitFor { source.active().isEmpty() }
            check(inventoryFile.readText().contains("\"schema\":2"))
            cases += "corrupt-inventory-withholds-new-posts-and-cleans-visible-cards"
        } finally {
            inventoryFile.writeBytes(original)
            source.clear()
        }
    }

    private fun payload() =
        ActionNotificationPayload(
            ActionNotificationReference("card", "render"),
            "Fictional coworker",
            "Fictional encrypted message",
            ActionNotificationKind.entries.associateWith { "${it.key}-envelope" },
            true,
        )

    private fun current(id: String) =
        manager.activeNotifications.single { it.tag == prefix + id && it.id == 0 }.notification

    private suspend fun waitFor(condition: suspend () -> Boolean) =
        withTimeout(5_000) { while (!condition()) delay(25) }
}
