package dev.fajar.fluent

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.notifications.data.datasources.AndroidNotificationPermissionSource
import dev.fajar.starter.security.data.lock.datasources.*

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val app = application as StarterApplication
        val container = app.container
        container.koin.get<AndroidNotificationPermissionSource>().attach(this)
        app.deviceAuthentication.attach(this)
        app.browserAuthorization.attach(this)
        if (savedInstanceState == null)
            (intent.dataString
                    ?: intent.getStringExtra("destination")?.let {
                        "${app.environment.linkScheme}://app/$it"
                    })
                ?.let(app.links::receive)
        setContent { StarterApp(container, app.links.links) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val app = application as StarterApplication
        (intent.dataString
                ?: intent.getStringExtra("destination")?.let {
                    "${app.environment.linkScheme}://app/$it"
                })
            ?.let(app.links::receive)
    }

    override fun onDestroy() {
        (application as StarterApplication)
            .container
            .koin
            .get<AndroidNotificationPermissionSource>()
            .detach(this)
        (application as StarterApplication).deviceAuthentication.detach(this)
        (application as StarterApplication).browserAuthorization.detach(this)
        super.onDestroy()
    }
}
