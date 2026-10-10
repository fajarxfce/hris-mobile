package dev.fajar.starter.dashboard.presentation.navigation

import dev.fajar.starter.dashboard.presentation.home.DashboardTab
import kotlinx.serialization.Serializable

/** Navigation encodes primitives consistently across Android, native, desktop, and Web. */
@Serializable
@ConsistentCopyVisibility
data class DashboardRoute private constructor(val tab: String) {
    constructor(tab: DashboardTab = DashboardTab.Overview) : this(tab.name)
}
