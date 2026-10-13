package dev.fajar.starter.expenses.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.data.repositories.RemoteExpensePreparationRepository
import dev.fajar.starter.expenses.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class ExpensePreparationMappingTest {
    @Test
    fun categoryMappingKeepsExactEffectivePolicyAndBothContractKinds() = runTest {
        val f = ExpenseDataFixture()
        f.categories =
            ExpensePageDto(
                listOf(
                    f.category.copy(
                        maximumLineAmount = "999999999999.98",
                        maximumClaimAmount = "999999999999.99",
                        version = 5,
                        appliedRevision = 3,
                        active = false,
                    )
                ),
                "TRAVEL",
            )
        val page =
            assertIs<AppResult.Success<ExpensePage<ExpenseCategory>>>(
                    RemoteExpensePreparationRepository(f)
                        .categories(f.access, LocalDate(2026, 10, 13), null)
                )
                .value
        val category = page.items.single()
        assertEquals("999999999999.98", category.maximumLineAmount.value)
        assertEquals("999999999999.99", category.maximumClaimAmount.value)
        assertEquals(ExpenseContractKind.entries.toSet(), category.allowedContracts)
        assertEquals(3L, category.appliedRevision)
        assertFalse(category.active)
        assertEquals("TRAVEL", page.nextCursor)
    }

    @Test
    fun invalidCategoryPoliciesFailInsideTheRepositoryBoundary() = runTest {
        val f = ExpenseDataFixture()
        val c = f.category
        val invalid =
            listOf(
                c.copy(id = "invalid"),
                c.copy(code = "with-dashes"),
                c.copy(name = ""),
                c.copy(name = "x".repeat(121)),
                c.copy(currency = "USD"),
                c.copy(effectiveFrom = "2026-02-30"),
                c.copy(effectiveFrom = "2026-10-14"),
                c.copy(maximumLineAmount = "0"),
                c.copy(maximumLineAmount = "1.001"),
                c.copy(maximumClaimAmount = "100"),
                c.copy(maximumClaimAmount = "1000000000000"),
                c.copy(maximumAgeDays = 0),
                c.copy(maximumAgeDays = 367),
                c.copy(allowedContracts = emptyList()),
                c.copy(allowedContracts = listOf("PERMANENT", "PERMANENT")),
                c.copy(allowedContracts = listOf("UNKNOWN")),
                c.copy(version = -1),
                c.copy(appliedRevision = 1),
                c.copy(version = 9_007_199_254_740_992L),
            )
        val repo = RemoteExpensePreparationRepository(f)
        for (category in invalid) {
            f.categories = ExpensePageDto(listOf(category), null)
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repo.categories(f.access, LocalDate(2026, 10, 13), null))
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun categoryPagesRejectDuplicatesOversizedAndNonAdvancingCursors() = runTest {
        val f = ExpenseDataFixture()
        val repo = RemoteExpensePreparationRepository(f)
        for (page in
            listOf(
                ExpensePageDto(listOf(f.category, f.category), null),
                ExpensePageDto(listOf(f.category, f.category.copy(id = f.claim.id)), null),
                ExpensePageDto(List(21) { f.category }, null),
                ExpensePageDto(listOf(f.category), "OTHER"),
                ExpensePageDto(emptyList(), "TRAVEL"),
            )) {
            f.categories = page
            assertIs<AppResult.Failed>(repo.categories(f.access, LocalDate(2026, 10, 13), null))
        }
        f.categories = ExpensePageDto(listOf(f.category), "TRAVEL")
        assertIs<AppResult.Failed>(repo.categories(f.access, LocalDate(2026, 10, 13), "TRAVEL"))
    }

    @Test
    fun costCentersValidateMinimalFactsAndCursorProgressWithoutAssumingServerCollation() = runTest {
        val f = ExpenseDataFixture()
        val repo = RemoteExpensePreparationRepository(f)
        for (center in
            listOf(
                f.costCenter.copy(id = "invalid"),
                f.costCenter.copy(code = " "),
                f.costCenter.copy(name = ""),
                f.costCenter.copy(name = "x".repeat(201)),
                f.costCenter.copy(version = -1),
            )) {
            f.costCenters = ExpensePageDto(listOf(center), null)
            assertIs<AppResult.Failed>(repo.costCenters(f.access, "", null))
        }
        for (page in
            listOf(
                ExpensePageDto(listOf(f.costCenter, f.costCenter), null),
                ExpensePageDto(List(21) { f.costCenter }, null),
                ExpensePageDto(listOf(f.costCenter), "DEPARTMENT:CC01"),
            )) {
            f.costCenters = page
            assertIs<AppResult.Failed>(repo.costCenters(f.access, "", null))
        }
        f.costCenters = ExpensePageDto(listOf(f.costCenter), "COST_CENTER:CC01")
        assertIs<AppResult.Failed>(repo.costCenters(f.access, "", "COST_CENTER:CC01"))
        f.costCenters =
            ExpensePageDto(
                listOf(
                    f.costCenter.copy(code = "A_0"),
                    f.costCenter.copy(id = f.claim.id, code = "A-Z"),
                ),
                "COST_CENTER:A-Z",
            )
        assertEquals(
            2,
            assertIs<AppResult.Success<ExpensePage<ExpenseCostCenter>>>(
                    repo.costCenters(f.access, "", null)
                )
                .value
                .items
                .size,
        )
    }

    @Test
    fun datasourcePreservesExceptionsRepositoryMapsThemAndCancellationAlwaysEscapes() = runTest {
        val f = ExpenseDataFixture()
        val repo = RemoteExpensePreparationRepository(f)
        val technical = IllegalStateException("private technical context")
        f.failure = technical
        assertSame(
            technical,
            assertFailsWith<IllegalStateException> {
                f.categories(f.access.sessionId, f.access.companyId, "2026-10-13", null)
            },
        )
        val failure =
            assertIs<AppResult.Failed>(repo.categories(f.access, LocalDate(2026, 10, 13), null))
                .failure
        assertEquals(FailureKind.Unexpected, failure.kind)
        assertFalse(failure.message.contains("private"))
        val cancelled = CancellationException("cancelled")
        f.failure = cancelled
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> {
                repo.categories(f.access, LocalDate(2026, 10, 13), null)
            },
        )
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> { repo.costCenters(f.access, "", null) },
        )
    }
}
