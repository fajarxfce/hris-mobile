package dev.fajar.starter.expenses.data

import dev.fajar.starter.expenses.data.datasources.ExpenseRemoteDataSource
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.data.mappers.*
import dev.fajar.starter.expenses.domain.entities.*

class ExpenseCommandDataFixture {
    val f = ExpenseDataFixture()
    val access = f.access
    val create =
        ExpenseCommand(
            "10000000-0000-4000-8000-000000000001",
            access.accountId,
            access.audience,
            access.companyId,
            f.claim.id,
            f.employee.id,
            f.employee.employeeNumber,
            null,
            access.timezone,
            ExpenseCommandPayload.SaveDraft(
                null,
                f.draft.title,
                f.draft.description,
                listOf(f.line.toLine()),
            ),
            "Saved",
            f.now,
        )
    val edit =
        create.copy(
            id = "10000000-0000-4000-8000-000000000002",
            payload = (create.payload as ExpenseCommandPayload.SaveDraft).copy(expectedVersion = 0),
        )
    val submit =
        create.copy(
            id = "10000000-0000-4000-8000-000000000003",
            payload = ExpenseCommandPayload.Submit(1, f.submission.id),
        )
    val cancel =
        create.copy(
            id = "10000000-0000-4000-8000-000000000004",
            payload = ExpenseCommandPayload.Cancel(1),
        )
    val withdraw =
        create.copy(
            id = "10000000-0000-4000-8000-000000000005",
            payload = ExpenseCommandPayload.Withdraw(2, f.submission.id),
        )
    val commands = listOf(create, edit, submit, cancel, withdraw)
    var receipt = ExpenseCommandReceiptDto(f.claim.id, 0)
    var failure: Exception? = null
    var calls = 0
    val source =
        object : ExpenseRemoteDataSource by f {
            override suspend fun saveDraft(
                sessionId: String,
                companyId: String,
                id: String,
                operationId: String,
                request: ExpenseSaveDraftRequest,
            ): ExpenseCommandReceiptDto {
                calls++
                failure?.let { throw it }
                return receipt
            }

            override suspend fun submit(
                sessionId: String,
                companyId: String,
                id: String,
                operationId: String,
                request: ExpenseSubmitRequest,
            ): ExpenseCommandReceiptDto {
                calls++
                failure?.let { throw it }
                return receipt
            }

            override suspend fun cancel(
                sessionId: String,
                companyId: String,
                id: String,
                operationId: String,
                request: ExpenseActionRequest,
            ): ExpenseCommandReceiptDto {
                calls++
                failure?.let { throw it }
                return receipt
            }

            override suspend fun withdraw(
                sessionId: String,
                companyId: String,
                id: String,
                operationId: String,
                request: ExpenseActionRequest,
            ): ExpenseCommandReceiptDto {
                calls++
                failure?.let { throw it }
                return receipt
            }
        }
}
