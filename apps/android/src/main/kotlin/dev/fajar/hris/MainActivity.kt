package dev.fajar.hris

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.notifications.data.datasources.AndroidNotificationPermissionSource
import dev.fajar.starter.notifications.data.dto.ActionNotificationKind
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
        app.googleCredentials.attach(this)
        app.location.attach(this)
        app.fileInputs.attach(this)
        app.fileOutputs.attach(this)
        if (savedInstanceState == null || intent.action == NetworkInspectionShortcut.ACTION)
            receiveLink(app, intent)
        setContent { StarterApp(container, app.links.links) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val app = application as StarterApplication
        receiveLink(app, intent)
    }

    private fun receiveLink(app: StarterApplication, intent: Intent) {
        if (intent.action == NetworkInspectionShortcut.ACTION) {
            app.openNetworkInspector()
            setIntent(Intent(intent).setAction(null))
            return
        }
        val native =
            try {
                app.notificationIntents.input(intent)
            } catch (_: RuntimeException) {
                null
            }
        if (native != null) {
            if (native.kind == ActionNotificationKind.Open && native.replyText == null)
                app.links.receive(native)
            return
        }
        if (intent.data?.scheme == "owned-notification") return
        (intent.dataString
                ?: intent.getStringExtra("destination")?.let {
                    "${app.environment.linkScheme}://app/$it"
                })
            ?.let(app.links::receive)
    }

    override fun onResume() {
        super.onResume()
        (application as StarterApplication).refreshNetworkInspectorShortcut()
    }

    override fun onDestroy() {
        (application as StarterApplication)
            .container
            .koin
            .get<AndroidNotificationPermissionSource>()
            .detach(this)
        (application as StarterApplication).deviceAuthentication.detach(this)
        (application as StarterApplication).browserAuthorization.detach(this)
        (application as StarterApplication).googleCredentials.detach(this)
        (application as StarterApplication).location.detach(this)
        (application as StarterApplication).fileInputs.detach(this)
        (application as StarterApplication).fileOutputs.detach(this)
        super.onDestroy()
    }
}
