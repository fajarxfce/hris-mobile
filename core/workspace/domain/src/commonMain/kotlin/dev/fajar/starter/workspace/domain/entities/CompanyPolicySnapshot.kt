package dev.fajar.starter.workspace.domain.entities

import kotlin.time.Instant

/** Request time is local; server timestamps define the maximum freshness interval. */
data class CompanyPolicySnapshot(val policy: CompanyPolicy, val requestedAt: Instant)
