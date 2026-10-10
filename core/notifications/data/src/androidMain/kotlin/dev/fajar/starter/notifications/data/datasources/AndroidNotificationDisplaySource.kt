package dev.fajar.starter.notifications.data.datasources

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.fajar.starter.notifications.data.dto.NotificationPayload
import dev.fajar.starter.notifications.data.errors.NotificationPermissionException

class AndroidNotificationDisplaySource(
    private val context: Context,
    private val iconResource: Int,
    private val scheme: String,
) : NotificationDisplaySource {
    override suspend fun show(payload: NotificationPayload) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        )
            throw NotificationPermissionException()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            manager.createNotificationChannel(
                NotificationChannel("updates", "Updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
        val link =
            Uri.Builder().scheme(scheme).authority("app").appendPath(payload.destination).build()
        val intent =
            Intent(Intent.ACTION_VIEW, link)
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending =
            PendingIntent.getActivity(
                context,
                payload.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, "updates")
                .setSmallIcon(iconResource)
                .setContentTitle(payload.title)
                .setContentText(payload.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        NotificationManagerCompat.from(context).notify(payload.id.hashCode(), notification)
    }
}
