package dev.fajar.starter.identity.domain

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.identity.domain.usecases.ObserveSessionIdentity
import kotlin.test.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class ObserveSessionIdentityTest {
    @Test
    fun tokenRotationDoesNotResetNavigationButANewSessionForTheSameUserDoes() = runTest {
        val original =
            Session(
                "first",
                User("one", "Example", "example@example.invalid"),
                SessionTokens("access", "refresh", 100),
            )
        val repository =
            object : SessionRepository {
                override val persistent = false

                override suspend fun current() = AppResult.Success(original)

                override suspend fun compareAndSet(expected: Session?, updated: Session?) =
                    error("Read only")

                override fun observe() =
                    flowOf(
                        AppResult.Success(original),
                        AppResult.Success(original.copy(pendingRefreshOperationId = "operation")),
                        AppResult.Success(
                            original.copy(tokens = SessionTokens("next", "next-refresh", 200))
                        ),
                        AppResult.Success(original.copy(id = "second")),
                        AppResult.Success(null),
                    )
            }
        assertEquals(
            listOf(
                AppResult.Success(SessionIdentity("first", original.user)),
                AppResult.Success(SessionIdentity("second", original.user)),
                AppResult.Success(null),
            ),
            ObserveSessionIdentity(repository)().toList(),
        )
    }
}
