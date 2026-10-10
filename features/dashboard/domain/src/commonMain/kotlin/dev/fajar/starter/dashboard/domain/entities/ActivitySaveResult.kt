package dev.fajar.starter.dashboard.domain.entities

import dev.fajar.starter.common.result.Failure

/** A scheduling failure does not undo an already committed local change. */
data class ActivitySaveResult(val schedulingFailure: Failure? = null)
