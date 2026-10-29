@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.fajar.starter.identity.data.firebase.datasources

import dev.fajar.starter.identity.data.firebase.dto.FirebaseClientOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.Foundation.*

/** Reads only the selected application's bundled public configuration. */
class AppleFirebaseClientOptionsSource(private val bundle: NSBundle = NSBundle.mainBundle) :
    FirebaseClientOptionsSource {
    override suspend fun load(): FirebaseClientOptions? =
        withContext(Dispatchers.Default) {
            val path =
                bundle.pathForResource("GoogleService-Info", "plist") ?: return@withContext null
            val data = requireNotNull(NSData.dataWithContentsOfFile(path))
            require(data.length in 1uL..32_768uL)
            val values =
                requireNotNull(
                    NSPropertyListSerialization.propertyListWithData(
                        data,
                        NSPropertyListImmutable,
                        null,
                        null,
                    ) as? Map<*, *>
                )
            val bundleId = requireNotNull(bundle.bundleIdentifier)
            require(values["BUNDLE_ID"] == bundleId)
            coroutineContext.ensureActive()
            FirebaseClientOptions(
                projectId = requireNotNull(values["PROJECT_ID"] as? String),
                apiKey = requireNotNull(values["API_KEY"] as? String),
                iosBundleId = bundleId,
            )
        }
}
