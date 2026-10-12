package dev.fajar.starter.workforce.presentation.overtime.detail

import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeRequestSelection

data class OvertimeRequestTarget(val workspaceId: String, val selection: OvertimeRequestSelection)
