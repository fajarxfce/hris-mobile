package dev.fajar.starter.workspace.domain.entities

import kotlin.time.Instant

/**
 * Last complete acquisition of a published read model. A null timestamp means the feed has not
 * completed; individually acquired details or unfinished input may still exist locally.
 */
data class CompanyDataStatus(val lastCompletedAt: Instant?)
