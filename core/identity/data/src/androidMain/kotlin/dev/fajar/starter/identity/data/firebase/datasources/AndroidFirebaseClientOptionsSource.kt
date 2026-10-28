package dev.fajar.starter.identity.data.firebase.datasources

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.fajar.starter.identity.data.firebase.dto.FirebaseClientOptions
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Google Services generates optional app resources; no Firebase Auth SDK session is created. */
class AndroidFirebaseClientOptionsSource(context: Context) : FirebaseClientOptionsSource {
    private val application = context.applicationContext

    @Suppress("DEPRECATION", "DiscouragedApi")
    override suspend fun load(): FirebaseClientOptions? =
        withContext(Dispatchers.IO) {
            fun resource(name: String): String? {
                val id =
                    application.resources.getIdentifier(name, "string", application.packageName)
                return if (id == 0) null else application.getString(id).takeIf { it.isNotBlank() }
            }
            val projectId = resource("project_id")
            val apiKey = resource("google_api_key")
            if (projectId == null && apiKey == null) return@withContext null
            val info =
                application.packageManager.getPackageInfo(
                    application.packageName,
                    if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                    else PackageManager.GET_SIGNATURES,
                )
            val signatures =
                if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
                else info.signatures
            val signature = requireNotNull(signatures).single()
            val certificate =
                MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()).joinToString(
                    ""
                ) {
                    "%02X".format(it.toInt() and 255)
                }
            FirebaseClientOptions(
                requireNotNull(projectId),
                requireNotNull(apiKey),
                resource("default_web_client_id"),
                application.packageName,
                certificate,
            )
        }
}
