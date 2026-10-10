package dev.fajar.starter.featureflags.data.datasources

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import dev.fajar.starter.featureflags.data.errors.RemoteConfigUnavailableException
import kotlinx.coroutines.tasks.await

class AndroidFeatureFlagSource(private val context: Context) : RemoteFeatureFlagSource {
    override suspend fun fetch(): Map<String, String> {
        if (FirebaseApp.getApps(context).isEmpty()) throw RemoteConfigUnavailableException()
        val client = FirebaseRemoteConfig.getInstance()
        client
            .setConfigSettingsAsync(
                FirebaseRemoteConfigSettings.Builder().setFetchTimeoutInSeconds(15).build()
            )
            .await()
        // The shared use case owns the interval; the SDK still enforces server throttling.
        client.fetch(0).await()
        client.activate().await()
        return client.all
            .filterValues { it.source == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE }
            .mapValues { it.value.asString() }
    }
}
