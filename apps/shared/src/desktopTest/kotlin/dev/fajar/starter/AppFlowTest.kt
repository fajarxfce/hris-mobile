package dev.fajar.starter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.app.di.applicationNetworkModule
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.fixtures.NativeUiServer
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.notifications.data.dto.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import org.koin.dsl.module

class AppFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun nativeLoginCompanySwitchLanguageAndLogout() =
        withApplication(NativeUiServer()) {
            signIn()
            waitFor("Choose a company")
            capture("companies")
            compose.onAllNodesWithText("Open company")[0].performClick()
            waitFor("Employee workspace")
            compose.onNodeWithText("PT Nusa Digital").assertExists()
            capture("home")
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.onNodeWithText("Alex Morgan").assertExists()
            compose.onNodeWithText("Settings").performScrollTo().performClick()
            compose.onNodeWithText("Bahasa Indonesia").performClick()
            waitFor("Pengaturan")
            compose.onNodeWithText("Kembali").performClick()
            compose.onNodeWithText("Pengaturan").performScrollTo().performClick()
            compose.onNodeWithText("English").performClick()
            waitFor("Settings")
            compose.onNodeWithText("Back").performClick()
            compose.onNodeWithText("Change company").performScrollTo().performClick()
            waitFor("Choose a company")
            compose.onAllNodesWithText("Open company")[1].performClick()
            waitFor("Employee workspace")
            compose.onNodeWithText("PT Lintas Karya").assertExists()
            compose.onNodeWithText("PT Nusa Digital").assertDoesNotExist()
            compose.onNode(hasText("Account") and hasClickAction()).performClick()
            compose.onNodeWithText("Sign out").performScrollTo().performClick()
            waitFor("Use your account to continue.")
            compose.onNodeWithText("PT Lintas Karya").assertDoesNotExist()
            compose.onNodeWithText("Use demo account").assertDoesNotExist()
        }

    @Test
    fun employeeProfileLoadsOwnAssignmentsAndClearsWhenTheCompanyChanges() =
        withApplication(NativeUiServer()) {
            signIn()
            waitFor("Choose a company")
            compose.onAllNodesWithText("Open company")[0].performClick()
            waitFor("Employee workspace")
            compose.onNodeWithText("My profile").performClick()
            waitFor("Personal information")
            compose.onNodeWithText("1996-02-29").assertExists()
            capture("profile")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Mobile Engineer"))
            compose.onNodeWithText("Mobile Engineer").assertIsDisplayed()
            compose.onNodeWithText("Engineering").assertExists()
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Employee workspace")
            compose.onNodeWithText("Change company").performClick()
            waitFor("Choose a company")
            compose.onAllNodesWithText("Open company")[1].performClick()
            waitFor("Employee workspace")
            compose.onNodeWithText("My profile").performClick()
            waitFor("No employee profile is available. Contact HR to check your account linkage.")
            compose.onNodeWithText("1996-02-29").assertDoesNotExist()
            compose.onNodeWithText("Engineering").assertDoesNotExist()
        }

    @Test
    fun revokedProfileAccessRemovesPreviouslyDisplayedPrivateFields() {
        val server = NativeUiServer()
        withApplication(server) {
            signIn()
            waitFor("Choose a company")
            compose.onAllNodesWithText("Open company")[0].performClick()
            waitFor("Employee workspace")
            compose.onNodeWithText("My profile").performClick()
            waitFor("Personal information")
            server.people.denied = true
            compose.onNodeWithText("Refresh").performClick()
            waitFor("Your account cannot perform this action.")
            compose.onNodeWithText("1996-02-29").assertDoesNotExist()
            compose.onNodeWithText("Private server detail").assertDoesNotExist()
        }
    }

    @Test
    fun expiredProfileAssuranceCanBeRenewedWithoutChangingCompany() {
        val server = NativeUiServer(requireMfa = true, existingAuthenticator = true)
        withApplication(server) {
            signIn()
            waitFor("Verify your account")
            compose.onNodeWithContentDescription("Verification code").performTextInput("123456")
            compose.onNode(hasText("Verify") and hasClickAction()).performScrollTo().performClick()
            waitFor("Choose a company")
            compose.onAllNodesWithText("Open company")[0].performClick()
            waitFor("Employee workspace")
            compose.onNodeWithText("My profile").performClick()
            waitFor("Personal information")
            kotlinx.coroutines.runBlocking { server.expireVerification() }
            compose.onNodeWithText("Refresh").performClick()
            waitFor("Your account cannot perform this action.")
            compose.onNodeWithText("1996-02-29").assertDoesNotExist()
            compose.onNode(hasText("Verify your account") and hasClickAction()).performClick()
            waitFor("Verification code")
            compose.onNodeWithContentDescription("Verification code").performTextInput("123456")
            compose.onNode(hasText("Verify") and hasClickAction()).performScrollTo().performClick()
            waitFor("Personal information")
            compose.onNodeWithText("1996-02-29").assertExists()
            compose.onNodeWithText("Choose a company").assertDoesNotExist()
            assertEquals(0, server.refreshes)
        }
    }

    @Test
    fun enrollmentKeepsRecoveryCodesVisibleUntilExplicitAcknowledgement() {
        val server = NativeUiServer(requireMfa = true)
        withApplication(server) {
            signIn()
            waitFor("Set up authenticator")
            compose.onNode(hasText("Set up authenticator") and hasClickAction()).performClick()
            waitFor("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
            compose.onNodeWithContentDescription("Verification code").performTextInput("123456")
            compose.onNode(hasText("Verify") and hasClickAction()).performScrollTo().performClick()
            waitFor("Recovery codes")
            compose.onNodeWithText("Choose a company").assertDoesNotExist()
            compose.onNodeWithText("RECOVERY-TEST-00").assertExists()
            assertEquals(1, server.confirmations)
            compose.onNodeWithText("I have saved these codes").performScrollTo().performClick()
            waitFor("Choose a company")
            compose.onNodeWithText("RECOVERY-TEST-00").assertDoesNotExist()
        }
    }

    @Test
    fun anEnrolledAuthenticatorSkipsSetupAndInvalidProofDoesNotRefreshTokens() {
        val server = NativeUiServer(requireMfa = true, existingAuthenticator = true)
        withApplication(server) {
            signIn()
            waitFor("Verify your account")
            compose.onNodeWithText("Set up authenticator").assertDoesNotExist()
            compose.onNodeWithContentDescription("Verification code").performTextInput("654321")
            compose.onNode(hasText("Verify") and hasClickAction()).performScrollTo().performClick()
            waitFor(
                "The code is invalid or has already been used. Check your authenticator or use another recovery code."
            )
            assertEquals(0, server.refreshes)
            compose.onNodeWithText("Private diagnostic must not be displayed").assertDoesNotExist()
            compose
                .onNodeWithContentDescription("Verification code")
                .performTextReplacement("123456")
            compose.onNode(hasText("Verify") and hasClickAction()).performScrollTo().performClick()
            waitFor("Choose a company")
        }
    }

    @Test
    fun aLostConfirmationRequiresFreshSignInInsteadOfReplayingTheProof() {
        val server = NativeUiServer(requireMfa = true, loseConfirmation = true)
        withApplication(server) {
            signIn()
            waitFor("Set up authenticator")
            compose.onNode(hasText("Set up authenticator") and hasClickAction()).performClick()
            waitFor("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
            compose.onNodeWithContentDescription("Verification code").performTextInput("123456")
            compose.onNode(hasText("Verify") and hasClickAction()).performScrollTo().performClick()
            waitFor(
                "The verification result could not be confirmed. Sign out and sign in again before retrying."
            )
            compose.onNode(hasText("Verify") and hasClickAction()).assertIsNotEnabled()
            assertEquals(1, server.confirmations)
            assertEquals(0, server.refreshes)
        }
    }

    private fun withApplication(server: NativeUiServer, test: () -> Unit) {
        val directory = Files.createTempDirectory("hris-ui").toFile()
        val container =
            createAppContainer(
                createUserPreferences(directory),
                createAppDatabase(directory),
                module {
                    single<NotificationPermissionSource> {
                        object : NotificationPermissionSource {
                            override suspend fun check() = NotificationPermission.Granted

                            override suspend fun request() = NotificationPermission.Granted
                        }
                    }
                    single<NotificationDisplaySource> {
                        object : NotificationDisplaySource {
                            override suspend fun show(payload: NotificationPayload) = Unit
                        }
                    }
                    single<PushTokenSource> { UnavailablePushTokenSource() }
                },
                platform = AppPlatform.Android,
                http = applicationNetworkModule(AppEnvironment.Dev, server::engine),
            )
        val visible = mutableStateOf(true)
        try {
            compose.setContent {
                val lifecycleOwner = remember {
                    object : LifecycleOwner {
                        override val lifecycle =
                            LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
                    }
                }
                DisposableEffect(lifecycleOwner) {
                    onDispose { lifecycleOwner.lifecycle.currentState = Lifecycle.State.DESTROYED }
                }
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    if (visible.value) Box(Modifier.size(420.dp, 760.dp)) { StarterApp(container) }
                }
            }
            test()
            assertTrue(
                server.unexpectedPaths.isEmpty(),
                "Unexpected endpoints: ${server.unexpectedPaths}",
            )
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            container.close()
            directory.deleteRecursively()
        }
    }

    private fun signIn() {
        waitFor("Employee services")
        compose.onNodeWithText("Skip").performClick()
        waitFor("Use your account to continue.")
        compose.onNodeWithContentDescription("Email").performTextInput("alex@example.com")
        compose.onNodeWithContentDescription("Password").performTextInput("Fictional-password-123!")
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithContentDescription("Hide password").assertExists().performClick()
        compose.onNode(hasText("Sign in") and hasClickAction()).performScrollTo().performClick()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asSkiaBitmap()
        Image.makeFromBitmap(bitmap).use { image ->
            requireNotNull(image.encodeToData()).use { data ->
                val file = File("build/reports/screenshots/$name.png")
                file.parentFile.mkdirs()
                file.writeBytes(data.bytes)
            }
        }
    }
}
