@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.presentation.overtime.action

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimeActionWorkingCopy
import kotlin.uuid.Uuid

/** Keep the acknowledged revision, one pending write and the latest unsent input. */
data class OvertimeActionEditPersistence(
    val stored: OvertimeActionWorkingCopy,
    val edited: OvertimeActionWorkingCopy = stored,
    val pending: OvertimeActionWorkingMutation? = null,
    val writing: Boolean = false,
    val failure: Failure? = null,
) {
    val saved: Boolean
        get() = pending == null && stored.revision == edited.revision && failure == null

    fun edit(input: OvertimeActionForm): OvertimeActionEditPersistence =
        if (input == edited.input) this
        else copy(edited = edited.copy(input = input, revision = Uuid.random().toString()))

    fun acknowledge(value: OvertimeActionWorkingCopy) =
        copy(
            stored = value,
            edited = if (edited.revision == value.revision) value else edited,
            pending = null,
            writing = false,
            failure = null,
        )
}

data class OvertimeActionWorkingMutation(
    val previous: OvertimeActionWorkingCopy,
    val updated: OvertimeActionWorkingCopy,
)
