package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.AccountSnapshot
import dev.fajar.starter.identity.domain.repositories.*

/** Account metadata is admitted only while the originating local session still owns the result. */
class LoadAccount(
    private val sessions: SessionRepository,
    private val accounts: AccountRepository,
) {
    suspend operator fun invoke(sessionId: String): AppResult<AccountSnapshot> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (session?.id != sessionId)
            return AppResult.Failed(
                Failure(FailureKind.Unauthorized, "Sign in again.", code = "client.session_changed")
            )
        val account =
            when (val result = accounts.load(sessionId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val current =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (
            current?.id != sessionId ||
                current.binding != session.binding ||
                account.user.id != session.user.id
        )
            return AppResult.Failed(
                Failure(FailureKind.Unauthorized, "Sign in again.", code = "client.session_changed")
            )
        return AppResult.Success(account)
    }
}
