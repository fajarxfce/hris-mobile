package dev.fajar.starter.expenses.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class ExpenseReferenceTest {
    @Test
    fun referenceQueryDeduplicatesSharedValuesAndRetainsUnavailableMetadata() = runTest {
        val p = ExpensePreparationFixture()
        val f = p.f
        val receipt = "77777777-7777-7777-7777-777777777777"
        val line = f.line.copy(costCenterId = p.center.id, receiptIds = listOf(receipt))
        val result =
            assertIs<AppResult.Success<ExpenseDraftReferences>>(
                    p.references(
                        f.workspace.id,
                        f.employee.id,
                        listOf(line, line.copy(id = receipt)),
                    )
                )
                .value
        assertEquals(setOf(line.categoryId), p.referenceQuery?.categoryIds)
        assertEquals(setOf(p.center.id), p.referenceQuery?.costCenterIds)
        assertEquals(setOf(receipt), p.referenceQuery?.receiptRevisionIds)
        assertNull(result.values.categories.single().name)
        assertNull(result.values.receipts.single().fileName)
        assertEquals(1, p.reads)
        assertEquals(0, f.claimReads + f.writes)
    }

    @Test
    fun malformedAndUnboundedReferenceRequestsStopBeforePolicyAndTransport() = runTest {
        val p = ExpensePreparationFixture()
        val f = p.f
        listOf(
                p.references(f.workspace.id, "../owner", emptyList()),
                p.references(f.workspace.id, f.employee.id, List(21) { f.line }),
                p.references(
                    f.workspace.id,
                    f.employee.id,
                    listOf(f.line.copy(categoryId = "wrong")),
                ),
                p.references(
                    f.workspace.id,
                    f.employee.id,
                    listOf(f.line.copy(costCenterId = "wrong")),
                ),
                p.references(
                    f.workspace.id,
                    f.employee.id,
                    listOf(f.line.copy(receiptIds = listOf("wrong"))),
                ),
                p.references(
                    f.workspace.id,
                    f.employee.id,
                    listOf(f.line.copy(receiptIds = List(4) { f.line.id })),
                ),
            )
            .forEach {
                assertEquals(
                    "invalid_expense_references",
                    assertIs<AppResult.Failed>(it).failure.code,
                )
            }
        assertEquals(0, p.reads + f.policyReads + f.claimReads)
    }

    @Test
    fun mismatchedEmployeeAndMissingOrExtraReferencesAreNotPublished() = runTest {
        val p = ExpensePreparationFixture()
        val f = p.f
        val valid =
            ExpenseReferenceValues(
                f.employee.id,
                listOf(ExpenseLabelReference(f.line.categoryId)),
                emptyList(),
                emptyList(),
            )
        for (values in
            listOf(
                valid.copy(employeeId = p.center.id),
                valid.copy(categories = emptyList()),
                valid.copy(categories = valid.categories + valid.categories),
                valid.copy(receipts = listOf(ExpenseReceiptReference(p.center.id))),
            )) {
            p.referenceValues = values
            assertEquals(
                "client.expense_reference_mismatch",
                assertIs<AppResult.Failed>(
                        p.references(f.workspace.id, f.employee.id, listOf(f.line))
                    )
                    .failure
                    .code,
            )
        }
        assertEquals(0, f.writes)
    }
}
