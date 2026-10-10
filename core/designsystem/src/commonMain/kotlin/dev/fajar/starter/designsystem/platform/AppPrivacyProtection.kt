package dev.fajar.starter.designsystem.platform

import androidx.compose.runtime.Composable

/** Android prevents capture while enabled. Other platforms require host-specific privacy covers. */
@Composable expect fun AppPrivacyProtection(enabled: Boolean)
