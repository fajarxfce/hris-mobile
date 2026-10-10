package dev.fajar.starter.notifications.data.datasources

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import dev.fajar.starter.notifications.data.dto.NotificationPermission
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Activity owns registration; detaching cancels a pending permission request. */
class AndroidNotificationPermissionSource(private val context: Context) :
    NotificationPermissionSource {
    private var owner: ComponentActivity? = null
    private var launcher: ActivityResultLauncher<String>? = null
    private var pending: CancellableContinuation<Boolean>? = null

    fun attach(activity: ComponentActivity) {
        pending?.cancel()
        pending = null
        owner = activity
        launcher =
            activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                granted ->
                if (owner === activity) {
                    pending?.takeIf { it.isActive }?.resume(granted)
                    pending = null
                }
            }
    }

    fun detach(activity: ComponentActivity) {
        if (owner !== activity) return
        owner = null
        launcher = null
        pending?.cancel()
        pending = null
    }

    override suspend fun check() =
        if (NotificationManagerCompat.from(context).areNotificationsEnabled())
            NotificationPermission.Granted
        else NotificationPermission.Denied

    override suspend fun request(): NotificationPermission =
        withContext(Dispatchers.Main.immediate) {
            if (Build.VERSION.SDK_INT < 33) return@withContext check()
            check(pending == null) { "A notification permission request is already active." }
            val registered = checkNotNull(launcher) { "No active Android permission launcher." }
            val granted = suspendCancellableCoroutine { continuation ->
                pending = continuation
                registered.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (granted) NotificationPermission.Granted else NotificationPermission.Denied
        }
}
