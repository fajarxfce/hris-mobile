@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.featureflags.data.datasources

import dev.fajar.starter.featureflags.data.errors.RemoteConfigUnavailableException
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json

class BrowserFeatureFlagSource : RemoteFeatureFlagSource {
    override suspend fun fetch(): Map<String, String> {
        if (!browserRemoteConfigConfigured()) throw RemoteConfigUnavailableException()
        if (!browserRemoteConfigSupported().await<JsBoolean>().toBoolean())
            throw RemoteConfigUnavailableException()
        return Json.decodeFromString(browserRemoteConfigValues().await<JsString>().toString())
    }
}

@JsFun("() => Boolean(self.FLUENT_FIREBASE?.firebase)")
private external fun browserRemoteConfigConfigured(): Boolean

@JsFun("async () => (await import('firebase/remote-config')).isSupported()")
private external fun browserRemoteConfigSupported(): Promise<JsBoolean>

@JsFun(
    """async () => {
    const app = await import('firebase/app');
    const remote = await import('firebase/remote-config');
    const firebaseApp = app.getApps()[0] || app.initializeApp(self.FLUENT_FIREBASE.firebase);
    const client = remote.getRemoteConfig(firebaseApp);
    client.settings.minimumFetchIntervalMillis = 0;
    client.settings.fetchTimeoutMillis = 15000;
    await remote.fetchAndActivate(client);
    return JSON.stringify(Object.fromEntries(Object.entries(remote.getAll(client))
        .filter(([, value]) => value.getSource() === 'remote')
        .map(([key, value]) => [key, value.asString()])));
}"""
)
private external fun browserRemoteConfigValues(): Promise<JsString>
