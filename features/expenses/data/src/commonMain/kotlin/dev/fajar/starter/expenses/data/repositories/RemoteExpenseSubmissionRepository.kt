package dev.fajar.starter.expenses.data.repositories

import dev.fajar.starter.expenses.data.datasources.ExpenseRemoteDataSource
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.data.mappers.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.repositories.ExpenseSubmissionRepository
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteExpenseSubmissionRepository(private val remote: ExpenseRemoteDataSource) :
    ExpenseSubmissionRepository {
    override suspend fun send(sessionId: String, command: ExpenseCommand) = safeApiCall {
        require(command.toDto().toCommand() == command)
        val receipt =
            when (val payload = command.payload) {
                is ExpenseCommandPayload.SaveDraft ->
                    remote.saveDraft(
                        sessionId,
                        command.companyId,
                        command.claimId,
                        command.id,
                        ExpenseSaveDraftRequest(
                            command.employeeId,
                            payload.expectedVersion,
                            payload.title,
                            payload.description,
                            payload.lines.map { it.toDto() },
                            command.reason,
                        ),
                    )
                is ExpenseCommandPayload.Submit ->
                    remote.submit(
                        sessionId,
                        command.companyId,
                        command.claimId,
                        command.id,
                        ExpenseSubmitRequest(
                            payload.submissionId,
                            payload.expectedVersion,
                            command.reason,
                        ),
                    )
                is ExpenseCommandPayload.Cancel ->
                    remote.cancel(
                        sessionId,
                        command.companyId,
                        command.claimId,
                        command.id,
                        ExpenseActionRequest(payload.expectedVersion, command.reason),
                    )
                is ExpenseCommandPayload.Withdraw ->
                    remote.withdraw(
                        sessionId,
                        command.companyId,
                        payload.submissionId,
                        command.id,
                        ExpenseActionRequest(payload.expectedVersion, command.reason),
                    )
            }
        receipt.toReceipt(command)
    }
}
