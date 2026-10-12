package tasks

import org.junit.Assert.assertThrows
import org.junit.Test

class ApiEndpointTest {
    @Test
    fun developmentAcceptsLoopbackAndPrivateIpv4() {
        listOf(
                "localhost",
                "127.0.0.1",
                "10.77.77.1",
                "172.16.0.1",
                "172.31.255.254",
                "192.168.1.1",
            )
            .forEach { requireApiEndpoint("dev", "http://$it:8088/api/v1/") }
    }

    @Test
    fun publicAndLinkLocalHttpAreRejected() {
        listOf(
                "example.com",
                "localhost.example.com",
                "8.8.8.8",
                "0.0.0.0",
                "169.254.169.254",
                "172.15.0.1",
                "172.32.0.1",
                "100.64.0.1",
                "10.077.77.1",
                "10.77.77.256",
            )
            .forEach { host ->
                assertThrows(IllegalArgumentException::class.java) {
                    requireApiEndpoint("dev", "http://$host/api/v1/")
                }
            }
    }

    @Test
    fun deployedEnvironmentsAlwaysRequireHttps() {
        listOf("staging", "prod").forEach { environment ->
            listOf("localhost", "127.0.0.1", "10.77.77.1", "example.com").forEach { host ->
                assertThrows(IllegalArgumentException::class.java) {
                    requireApiEndpoint(environment, "http://$host/api/v1/")
                }
                requireApiEndpoint(environment, "https://$host/api/v1/")
            }
        }
    }

    @Test
    fun credentialsQueriesFragmentsAndNonHttpSchemesAreRejected() {
        listOf(
                "https://user:password@example.com/api/",
                "http://user@10.77.77.1/api/",
                "https://example.com/api/?token=value",
                "http://10.77.77.1/api/#fragment",
                "file:///tmp/api/",
                "/api/v1/",
            )
            .forEach { url ->
                assertThrows(IllegalArgumentException::class.java) {
                    requireApiEndpoint("dev", url)
                }
            }
    }
}
