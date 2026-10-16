@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.presentation.overtime.plan

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimePlanWorkingCopy
import kotlin.uuid.Uuid

/** One acknowledged copy, one pending mutation and the latest editor input. */
data class OvertimePlanEditPersistence(
    val stored: OvertimePlanWorkingCopy,
    val edited: OvertimePlanWorkingCopy = stored,
    val pending: OvertimePlanWorkingMutation? = null,
    val writing: Boolean = false,
    val failure: Failure? = null,
) {
    val saved: Boolean
        get() = pending == null && stored.revision == edited.revision && failure == null

    fun edit(input: OvertimePlanForm): OvertimePlanEditPersistence =
        if (input == edited.input) this
        else copy(edited = edited.copy(input = input, revision = Uuid.random().toString()))

    fun acknowledge(value: OvertimePlanWorkingCopy) =
        copy(
            stored = value,
            edited = if (edited.revision == value.revision) value else edited,
            pending = null,
            writing = false,
            failure = null,
        )
}

data class OvertimePlanWorkingMutation(
    val previous: OvertimePlanWorkingCopy,
    val updated: OvertimePlanWorkingCopy,
)
