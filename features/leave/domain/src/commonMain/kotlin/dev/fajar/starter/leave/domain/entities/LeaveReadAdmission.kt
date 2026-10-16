package dev.fajar.starter.leave.domain.entities

import dev.fajar.starter.common.result.Failure

/** A known read denial remains separate from stored facts and cursor retention. */
data class LeaveReadAdmission(val revision: String?, val denial: Failure?)
