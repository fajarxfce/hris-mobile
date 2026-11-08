package dev.fajar.hris

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.fajar.inspector.NetworkInspection
import dev.fajar.inspector.notification.NetworkInspectorNotification
import kotlinx.coroutines.CoroutineScope

/** Android entry point only. The shared app graph owns the viewer and its security gates. */
class NetworkInspectionShortcut(
    context: Context,
    inspection: NetworkInspection,
    scope: CoroutineScope,
) : AutoCloseable {
    private val notification =
        NetworkInspectorNotification(
            context,
            inspection,
            scope,
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .setAction(ACTION)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
            R.drawable.ic_notification,
            context.getString(R.string.network_inspector_notification_title),
            context.getString(R.string.network_inspector_notification_text),
        )

    fun refresh() {
        notification.refresh()
    }

    override fun close() {
        notification.close()
    }

    companion object {
        const val ACTION = "dev.fajar.hris.action.OPEN_NETWORK_INSPECTOR"
    }
}
