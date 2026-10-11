package dev.fajar.starter.leave.data

import dev.fajar.starter.leave.domain.entities.*

class LeaveSubmissionDataFixture {
    val preparation = LeavePreparationDataFixture()
    val access = preparation.base.access
    val command =
        LeaveSubmissionCommand(
            "10000000-0000-4000-8000-000000000001",
            access.accountId,
            access.audience,
            access.companyId,
            preparation.base.request.id,
            preparation.selection,
            "a".repeat(64),
            "Annual leave",
            LeaveDays(3),
            "Family appointment\nPersonal leave",
            emptyList(),
            preparation.base.now,
        )
}
