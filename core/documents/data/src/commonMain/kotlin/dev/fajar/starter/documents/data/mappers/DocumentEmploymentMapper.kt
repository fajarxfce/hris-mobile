@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.mappers

import dev.fajar.starter.documents.data.dto.DocumentDirectoryDto
import dev.fajar.starter.documents.data.dto.DocumentEmploymentDto
import dev.fajar.starter.documents.domain.entities.*
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

fun DocumentDirectoryDto.toEmploymentPage(
    access: DocumentAccess,
    after: String?,
): DocumentEmploymentPage {
    require(accountId == access.accountId && companyId == access.companyId)
    require(employments.items.size <= 20 && timezone.length in 1..100 && asOf.length == 10)
    TimeZone.of(timezone)
    require(LocalDate.parse(asOf).year in 1900..9998)
    val items = employments.items.map(DocumentEmploymentDto::toEmployment)
    require(
        items.distinctBy { it.id }.size == items.size &&
            items.distinctBy { it.employeeNumber }.size == items.size
    )
    // The server's collation owns employee-number ordering. Validate cursor progress here.
    val cursor = employments.nextCursor
    require(
        cursor == null ||
            (items.isNotEmpty() && cursor == items.last().employeeNumber && cursor != after)
    )
    return DocumentEmploymentPage(items.toList(), asOf, timezone, cursor)
}

fun DocumentEmploymentDto.toEmployment(): DocumentEmployment {
    require(Uuid.parse(id).toString() == id)
    require(
        employeeNumber.isNotBlank() &&
            employeeNumber.length <= 32 &&
            employeeNumber.none { c -> c.code in 0..31 || c.code in 127..159 } &&
            version in 0..9_007_199_254_740_991L
    )
    return DocumentEmployment(id, employeeNumber, DocumentEmploymentStatus.valueOf(status), version)
}
