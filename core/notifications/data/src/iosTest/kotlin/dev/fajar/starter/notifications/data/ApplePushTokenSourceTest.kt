@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.notifications.data

import dev.fajar.starter.notifications.data.datasources.AppleFirebaseClient
import dev.fajar.starter.notifications.data.datasources.ApplePushTokenSource
import dev.fajar.starter.notifications.data.errors.NotificationUnavailableException
import kotlin.test.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import platform.Foundation.NSError

class ApplePushTokenSourceTest {
    @Test
    fun cancellationRejectsLateSdkDeletionAndASeparateRetryCanComplete() = runTest {
        val client = Client()
        val source = ApplePushTokenSource(client)
        var completed = false
        val first = launch {
            source.deleteToken()
            completed = true
        }
        runCurrent()
        val late = assertNotNull(client.deletion)
        first.cancel()
        runCurrent()
        late(null)
        runCurrent()
        assertFalse(completed)
        val retry = launch {
            source.deleteToken()
            completed = true
        }
        runCurrent()
        assertNotNull(client.deletion)(null)
        runCurrent()
        retry.join()
        assertTrue(completed)
        assertEquals(2, client.deletions)
        assertEquals(0, client.fetches)
    }

    @Test
    fun cancellationRejectsALateTokenAndUnconfiguredDeletionDoesNotCallTheSdk() = runTest {
        val client = Client()
        val source = ApplePushTokenSource(client)
        var value: String? = null
        val request = launch { value = source.token() }
        runCurrent()
        val callback = assertNotNull(client.fetch)
        request.cancel()
        runCurrent()
        callback("retired-token", null)
        runCurrent()
        assertNull(value)
        client.configured = false
        assertFailsWith<NotificationUnavailableException> { source.deleteToken() }
        assertEquals(0, client.deletions)
    }

    private class Client : AppleFirebaseClient {
        override var configured = true
        var fetches = 0
        var deletions = 0
        var fetch: ((String?, NSError?) -> Unit)? = null
        var deletion: ((NSError?) -> Unit)? = null

        override fun fetchToken(completion: (String?, NSError?) -> Unit) {
            fetches++
            fetch = completion
        }

        override fun deleteToken(completion: (NSError?) -> Unit) {
            deletions++
            deletion = completion
        }
    }
}
