package dev.fajar.starter.featureflags.data

import dev.fajar.starter.featureflags.data.datasources.BrowserFeatureFlagSource
import dev.fajar.starter.featureflags.data.errors.RemoteConfigUnavailableException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class BrowserFeatureFlagSourceTest {
    @Test
    fun unconfiguredHostReportsUnavailableWithoutNetworkAccess() = runTest {
        assertFailsWith<RemoteConfigUnavailableException> { BrowserFeatureFlagSource().fetch() }
    }
}
