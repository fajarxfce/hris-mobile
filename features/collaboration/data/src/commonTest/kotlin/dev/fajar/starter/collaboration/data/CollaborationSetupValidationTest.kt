package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CollaborationSetupValidationTest {
    @Test
    fun rejectsForeignOrMalformedGatewayIdentityCredentialsVersionsAndBounds() = runTest {
        for (body in
            listOf(
                setupGatewayJson.replace(setupOwner.accountId.replace("-", ""), "9".repeat(32)),
                setupGatewayJson.replace(SETUP_DEVICE.replace("-", ""), "9".repeat(32)),
                setupGatewayJson.replace("matrix.example.test", "matrix.example.test/path"),
                setupGatewayJson.replace(setupAccess, "fictional-native-token"),
                setupGatewayJson.replace(setupRefresh, setupAccess),
                setupGatewayJson.replace("\"version\":0", "\"version\":2"),
                setupGatewayJson.replace("2026-11-30", "2026-01-01"),
                setupGatewayJson.replace(SETUP_GATEWAY, "invalid"),
                " ".repeat(8193),
            )) {
            withSetupRepository(
                MockEngine {
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            ) { repository ->
                val result = repository.bootstrap(setupOwner, setupBootstrap)
                assertIs<AppResult.Failed>(result)
                assertFalse(result.toString().contains(setupAccess))
            }
        }
    }

    @Test
    fun rejectsMalformedStatusRatherThanTreatingItAsAnUnregisteredDevice() = runTest {
        for (body in
            listOf(
                "{}",
                setupDeviceJson.replace("ACTIVE", "UNKNOWN"),
                setupDeviceJson.replace("ANDROID", "WEB"),
                setupDeviceJson.replace("\"version\":1", "\"version\":-1"),
                setupDeviceJson.replace("SUCCEEDED", "unknown"),
                setupDeviceJson.replace(SETUP_DEVICE, "invalid"),
                setupDeviceJson.replace("2026-10-31T00:00:00Z", "2026-01-01T00:00:00Z"),
            )) {
            withSetupRepository(
                MockEngine {
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            ) { repository ->
                assertIs<AppResult.Failed>(repository.read(setupOwner))
            }
        }
    }

    @Test
    fun malformedOrForeignRequestsNeverReachTransport() = runTest {
        var calls = 0
        withSetupRepository(
            MockEngine {
                calls++
                error("No request expected")
            }
        ) { repository ->
            assertIs<AppResult.Failed>(
                repository.read(setupOwner.copy(audience = "https://foreign.example/"))
            )
            assertIs<AppResult.Failed>(
                repository.read(setupOwner.copy(companyId = "../../another"))
            )
            assertIs<AppResult.Failed>(
                repository.read(setupOwner.copy(nativeSessionId = "invalid"))
            )
            assertIs<AppResult.Failed>(
                repository.register(
                    setupOwner,
                    CollaborationRegistration(
                        "invalid",
                        CollaborationNativePlatform.ANDROID,
                        "Device",
                    ),
                )
            )
            assertIs<AppResult.Failed>(
                repository.register(
                    setupOwner,
                    CollaborationRegistration(
                        SETUP_OPERATION,
                        CollaborationNativePlatform.ANDROID,
                        "x".repeat(65),
                    ),
                )
            )
            assertIs<AppResult.Failed>(
                repository.bootstrap(setupOwner, setupBootstrap.copy(gatewayVersion = 999_999))
            )
            assertIs<AppResult.Failed>(
                repository.retry(
                    setupOwner,
                    CollaborationProvisioningRetry(SETUP_OPERATION, SETUP_DEVICE, 0, 999_999),
                )
            )
            assertEquals(0, calls)
        }
    }
}
