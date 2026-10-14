package dev.fajar.starter.workforce.domain.replica.entities

import dev.fajar.starter.common.result.Failure

/** A confirmed projection denial is separate from cursor retention and data freshness. */
data class WorkforceReadAdmission(val revision: String?, val denial: Failure?)
