package dev.fajar.starter.network

import dev.fajar.starter.common.result.AppResult
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ApplicationAvailabilityConfig {
    var check: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
}

/** Policy is supplied by app composition; the transport never reads configuration storage. */
val ApplicationAvailability =
    createClientPlugin("ApplicationAvailability", ::ApplicationAvailabilityConfig) {
        val check = pluginConfig.check
        onRequest { _, _ ->
            val result = check()
            currentCoroutineContext().ensureActive()
            when (result) {
                is AppResult.Failed -> throw RequestFailureException(result.failure)
                is AppResult.Success -> Unit
            }
        }
    }
