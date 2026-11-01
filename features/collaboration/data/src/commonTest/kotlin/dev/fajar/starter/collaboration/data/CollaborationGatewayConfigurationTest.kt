package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CollaborationGatewayConfigurationTest {
    @Test
    fun androidPrivateReleaseApiUsesTheGatewayOriginWithoutChangingItsPort() {
        assertEquals(
            "http://10.77.77.1:8088/",
            CollaborationSdkEndpoint.fromApiBaseUrl("http://10.77.77.1:8088/api/v1/", true).baseUrl,
        )
    }

    @Test
    fun httpsApiPathsAndIpv6KeepTheirConfiguredAuthority() {
        for ((api, expected) in
            listOf(
                "https://hris.example.test/api/v1/" to "https://hris.example.test/",
                "https://hris.example.test:8443/api/v1" to "https://hris.example.test:8443/",
                "https://hris.example.test/tenant/api/v1/" to "https://hris.example.test/",
                "https://[2001:db8::1]:8443/api/v1/" to "https://[2001:db8::1]:8443/",
                "https://hris.example.test/" to "https://hris.example.test/",
            )) assertEquals(expected, CollaborationSdkEndpoint.fromApiBaseUrl(api).baseUrl)
    }

    @Test
    fun originSelectionDoesNotSanitizeInvalidConfigurationOrRelaxHttps() {
        for (api in
            listOf(
                "http://10.77.77.1:8088/api/v1/",
                "https://user:password@hris.example.test/api/v1/",
                "https://hris.example.test/api/v1/?token=fixture",
                "https://hris.example.test/api/v1/#fragment",
                "ftp://hris.example.test/api/v1/",
            )) assertFailsWith<IllegalArgumentException> {
            CollaborationSdkEndpoint.fromApiBaseUrl(api)
        }
        for (api in
            listOf(
                "http://user:password@10.77.77.1:8088/api/v1/",
                "http://10.77.77.1:8088/api/v1/?token=fixture",
                "http://10.77.77.1:8088/api/v1/#fragment",
                "ftp://hris.example.test/api/v1/",
            )) assertFailsWith<IllegalArgumentException> {
            CollaborationSdkEndpoint.fromApiBaseUrl(api, true)
        }
        assertFailsWith<IllegalArgumentException> {
            CollaborationSdkEndpoint("https://hris.example.test/api/v1/")
        }
    }
}
