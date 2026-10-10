package dev.fajar.starter.identity.data.sso.datasources

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference
import net.openid.appauth.AuthorizationService

/** Activity owns attachment; each authorization owns its launcher and Custom Tabs service. */
class AndroidBrowserAuthorizationSource(context: Context) : BrowserAuthorizationSource {
    private val application = context.applicationContext
    private var owner = WeakReference<ComponentActivity>(null)
    private var active: AndroidBrowserAuthorizationSession? = null

    fun attach(activity: ComponentActivity) {
        active?.close()
        owner = WeakReference(activity)
    }

    fun detach(activity: ComponentActivity) {
        if (owner.get() !== activity) return
        active?.close()
        owner.clear()
    }

    override suspend fun open(redirectUri: String): BrowserAuthorizationSession {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Authorization resources require the main thread."
        }
        val activity = checkNotNull(owner.get()) { "No authorization host is attached." }
        check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            "The authorization host is not active."
        }
        check(active == null) { "An authorization resource is already active." }
        val service = AuthorizationService(application)
        return try {
            AndroidBrowserAuthorizationSession(service, activity.activityResultRegistry) {
                    active = null
                }
                .also { active = it }
        } catch (error: Exception) {
            service.dispose()
            throw error
        }
    }
}
