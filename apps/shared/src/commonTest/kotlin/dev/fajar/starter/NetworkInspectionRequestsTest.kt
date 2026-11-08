package dev.fajar.starter

import dev.fajar.starter.app.inspection.*
import kotlin.test.*

class NetworkInspectionRequestsTest {
    @Test
    fun tapSurvivesMissingNavigationCollectorAndOnlyItsAcknowledgementConsumesIt() {
        val requests = NetworkInspectionRequests()
        requests.request()
        val first = assertIs<NetworkInspectionLaunchState.Requested>(requests.state.value)
        // A new activity/graph can observe the tap without an earlier collector receiving it.
        assertSame(first, requests.state.value)
        requests.request()
        val latest = assertIs<NetworkInspectionLaunchState.Requested>(requests.state.value)
        requests.handled(first)
        assertSame(latest, requests.state.value)
        requests.handled(latest)
        assertEquals(NetworkInspectionLaunchState.Idle, requests.state.value)
    }

    @Test
    fun disposedContainerRejectsBothLateTapAndLateNavigationCompletion() {
        val requests = NetworkInspectionRequests()
        requests.request()
        val pending = assertIs<NetworkInspectionLaunchState.Requested>(requests.state.value)
        requests.close()
        requests.handled(pending)
        requests.request()
        assertEquals(NetworkInspectionLaunchState.Closed, requests.state.value)
    }
}
