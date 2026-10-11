package dev.fajar.starter.workforce.presentation.attendance

import dev.fajar.starter.localization.AppString
import kotlin.time.Instant

data class AttendanceStamp(val capturedAt: Instant, val status: AppString)
