package dev.fajar.starter.workforce.domain.overtime.entities

import dev.fajar.starter.common.result.Failure

/** A confirmed read denial remains independent of facts and their ordinary age. */
data class OvertimeReadAdmission(val revision: String?, val denial: Failure?)
