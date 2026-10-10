package dev.fajar.starter.transfers.data.api

import dev.fajar.starter.network.HttpStatusException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode

fun transferResourceSegment(value: String): String {
    require(
        value.length in 1..128 &&
            value.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }
    )
    return value
}

/** No response body is buffered or included in diagnostics for a rejected transfer. */
fun requireTransferStatus(response: HttpResponse, vararg accepted: HttpStatusCode) {
    if (response.status !in accepted) throw HttpStatusException(response.status.value)
}
