@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.identity.data

import dev.fajar.starter.identity.data.firebase.datasources.AppleFirebaseClientOptionsSource
import dev.fajar.starter.identity.data.firebase.mappers.validated
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSTemporaryDirectory

class AppleFirebaseClientOptionsTest {
    @Test
    fun absentConfigurationDoesNotAdvertiseAnInstalledProvider() = runTest {
        withBundle(null) { assertNull(AppleFirebaseClientOptionsSource(it).load()) }
    }

    @Test
    fun matchingBundleLoadsPasswordConfigurationWithoutAdvertisingGoogleInteraction() = runTest {
        withBundle(configuration()) {
            val options = assertNotNull(AppleFirebaseClientOptionsSource(it).load()).validated()
            assertEquals("fixture-project", options.projectId)
            assertEquals("A".repeat(39), options.apiKey)
            assertEquals("dev.fajar.fixture", options.iosBundleId)
            assertNull(options.androidPackage)
            assertNull(options.androidCertificate)
            assertNull(options.googleWebClientId)
            assertEquals("FirebaseClientOptions(<redacted>)", options.toString())
        }
    }

    @Test
    fun anotherEnvironmentCannotSupplyThisApplicationsProviderCredentials() = runTest {
        withBundle(configuration(bundle = "dev.fajar.fixture.production")) {
            assertFailsWith<IllegalArgumentException> {
                AppleFirebaseClientOptionsSource(it).load()
            }
        }
    }

    @Test
    fun malformedMissingAndOversizedInstalledConfigurationCannotBecomeUnavailableFallback() =
        runTest {
            for (input in
                listOf(
                    "not a property list",
                    plist("<array><string>wrong root type</string></array>"),
                    configuration().replace("<key>API_KEY</key>", "<key>MISSING_KEY</key>"),
                    configuration()
                        .replace("<string>fixture-project</string>", "<integer>1</integer>"),
                    " ".repeat(32_769),
                )) {
                withBundle(input) {
                    assertFailsWith<IllegalArgumentException> {
                        AppleFirebaseClientOptionsSource(it).load()
                    }
                }
            }
        }

    private fun configuration(bundle: String = "dev.fajar.fixture") =
        plist(
            "<dict><key>PROJECT_ID</key><string>fixture-project</string>" +
                "<key>API_KEY</key><string>${"A".repeat(39)}</string>" +
                "<key>BUNDLE_ID</key><string>$bundle</string>" +
                "<key>CLIENT_ID</key><string>123-ios.apps.googleusercontent.com</string></dict>"
        )

    private suspend fun withBundle(configuration: String?, block: suspend (NSBundle) -> Unit) {
        val path = NSTemporaryDirectory().toPath() / "firebase-options-${Uuid.random()}.bundle"
        FileSystem.SYSTEM.createDirectory(path, mustCreate = true)
        try {
            FileSystem.SYSTEM.write(path / "Info.plist") {
                writeUtf8(
                    plist(
                        "<dict><key>CFBundleIdentifier</key><string>dev.fajar.fixture</string>" +
                            "<key>CFBundlePackageType</key><string>BNDL</string></dict>"
                    )
                )
            }
            if (configuration != null)
                FileSystem.SYSTEM.write(path / "GoogleService-Info.plist") {
                    writeUtf8(configuration)
                }
            block(assertNotNull(NSBundle.bundleWithPath(path.toString())))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(path)
        }
    }

    private fun plist(content: String) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\">$content</plist>"
}
