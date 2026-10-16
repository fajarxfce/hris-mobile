@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.presentation.create

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.leave.domain.working.entities.LeaveWorkingCopy
import kotlin.uuid.Uuid

/** One acknowledged copy, one pending mutation and the latest editor input. */
data class LeaveEditPersistence(
    val stored: LeaveWorkingCopy,
    val edited: LeaveWorkingCopy = stored,
    val pending: LeaveWorkingMutation? = null,
    val writing: Boolean = false,
    val failure: Failure? = null,
) {
    val saved: Boolean
        get() = pending == null && stored.revision == edited.revision && failure == null

    fun edit(input: LeaveCreateForm): LeaveEditPersistence =
        if (input == edited.input) this
        else copy(edited = edited.copy(input = input, revision = Uuid.random().toString()))

    fun acknowledge(value: LeaveWorkingCopy) =
        copy(
            stored = value,
            edited = if (edited.revision == value.revision) value else edited,
            pending = null,
            writing = false,
            failure = null,
        )
}

data class LeaveWorkingMutation(val previous: LeaveWorkingCopy, val updated: LeaveWorkingCopy)
