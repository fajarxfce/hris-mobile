package dev.fajar.starter.fixtures

import io.ktor.http.*
import java.io.IOException
import kotlin.test.assertEquals

class NativeSessionsUi {
    val revoked = mutableSetOf<String>()
    var loseRevocation = false

    fun response(path: String, method: HttpMethod): Pair<String, HttpStatusCode>? {
        if (!path.startsWith("/api/v1/auth/native/sessions")) return null
        if (path == "/api/v1/auth/native/sessions") {
            assertEquals(HttpMethod.Get, method)
            val items =
                mapOf(CURRENT to "HRIS Mobile (android)", OTHER to "Test iPhone")
                    .filterKeys { it !in revoked }
                    .map { (id, name) ->
                        """{"id":"$id","deviceName":"$name","createdAt":"2026-10-11T00:00:00Z","expiresAt":"2099-01-01T00:00:00Z"}"""
                    }
            return "[${items.joinToString()}]" to HttpStatusCode.OK
        }
        assertEquals(HttpMethod.Delete, method)
        val id = path.substringAfterLast('/')
        if (id !in setOf(CURRENT, OTHER))
            return """{"code":"native_session_not_found"}""" to HttpStatusCode.NotFound
        revoked += id
        if (loseRevocation) throw IOException("Simulated lost reply")
        return "" to HttpStatusCode.NoContent
    }

    companion object {
        const val CURRENT = "44444444-4444-4444-8444-444444444444"
        const val OTHER = "55555555-5555-4555-8555-555555555555"
    }
}
