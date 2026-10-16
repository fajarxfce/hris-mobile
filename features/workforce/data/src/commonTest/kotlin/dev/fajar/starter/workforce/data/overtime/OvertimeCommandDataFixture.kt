package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.workforce.data.overtime.mappers.toInterval
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlinx.datetime.LocalDate

class OvertimeCommandDataFixture {
    val source = OvertimeDataFixture()
    val access = source.access
    val plan =
        OvertimeCommand(
            "30000000-0000-4000-8000-000000000001",
            access.accountId,
            access.audience,
            access.companyId,
            source.request.id,
            source.base.employee.id,
            "E01",
            null,
            LocalDate.parse(source.request.workDate),
            source.request.timezone,
            OvertimeCommandPayload.Plan(
                7,
                source.requested.toInterval(),
                "a".repeat(64),
                "40000000-0000-4000-8000-000000000001",
            ),
            "Inventory count\nEnd of quarter",
            source.base.now,
        )
    val actual =
        plan.copy(
            id = "30000000-0000-4000-8000-000000000002",
            payload =
                OvertimeCommandPayload.Actual(
                    4,
                    source.requested.toInterval(),
                    "40000000-0000-4000-8000-000000000002",
                ),
        )
    val withdrawal =
        plan.copy(
            id = "30000000-0000-4000-8000-000000000003",
            payload = OvertimeCommandPayload.Withdrawal(5, "40000000-0000-4000-8000-000000000003"),
        )
    val commands = listOf(plan, actual, withdrawal)
}
