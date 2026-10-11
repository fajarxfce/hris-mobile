package dev.fajar.starter.workspace.domain

import dev.fajar.starter.workspace.domain.entities.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

fun testCompanyPolicy(now: Instant = Clock.System.now()) =
    CompanyPolicy(
        null,
        CompanyModule.entries.toSet(),
        CompanyMinimumBuilds(0, 0, 0),
        null,
        false,
        now,
        now + 60.seconds,
    )
