@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.security.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.access.entities.*
import dev.fajar.starter.security.domain.access.repositories.AccessRepository
import dev.fajar.starter.security.domain.access.usecases.ObservePermission
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*

class PermissionExpiryTest {
    @Test
    fun actionAvailabilityExpiresWithoutAnotherNetworkResponse() = runTest {
        val session =
            Session(
                "one",
                User("u", "User", "u@example.com"),
                SessionTokens("a", "r", Long.MAX_VALUE),
            )
        val sessions =
            object : SessionRepository {
                override val persistent = false

                override fun observe() = flowOf(AppResult.Success(session))

                override suspend fun current() = AppResult.Success(session)

                override suspend fun compareAndSet(expected: Session?, updated: Session?) =
                    error("unused")
            }
        val grants = AccessSnapshot("one", setOf("editor"), setOf(Permission.SaveActivity), 1100)
        val access =
            object : AccessRepository {
                override fun observe(sessionId: String) = flowOf(AppResult.Success(grants))

                override suspend fun cached(sessionId: String) = AppResult.Success(grants)

                override suspend fun refresh(sessionId: String) = AppResult.Success(Unit)

                override suspend fun invalidate(sessionId: String) = AppResult.Success(Unit)
            }
        val clock =
            object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(1000 + testScheduler.currentTime)
            }
        val values = mutableListOf<Boolean>()
        backgroundScope.launch {
            ObservePermission(sessions, access, clock)(Permission.SaveActivity).collect {
                values += (it as AppResult.Success).value
            }
        }
        runCurrent()
        assertEquals(listOf(true), values)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(true, false), values)
    }
}
