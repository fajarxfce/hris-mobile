package dev.fajar.starter.common.config

import kotlin.test.*

class AppLinkPathTest {
    private val resolve = AppLinkPath(AppEnvironment.Dev, "links.example.invalid")

    @Test
    fun configuredHttpsKeepsPathCaseAndEnvironmentWhileAcceptingHostCase() {
        assertEquals("inbox", resolve("https://links.example.invalid/app/dev/inbox"))
        assertEquals("Account", resolve("HTTPS://LINKS.EXAMPLE.INVALID/app/dev/Account"))
        assertEquals("inbox", resolve("${AppEnvironment.Dev.linkScheme}://app/inbox"))
        assertEquals("account", resolve("#/account"))
        assertEquals(
            "accounts/a/companies/b/inbox/c",
            resolve("https://links.example.invalid/app/dev/accounts/a/companies/b/inbox/c"),
        )
        assertNull(
            AppLinkPath(AppEnvironment.Dev, null)("https://links.example.invalid/app/dev/inbox")
        )
        assertNull(
            AppLinkPath(AppEnvironment.Staging, "links.example.invalid")(
                "https://links.example.invalid/app/dev/inbox"
            )
        )
    }

    @Test
    fun rejectsAuthorityConfusionEncodingQueriesFragmentsAndAmbiguousPaths() {
        listOf(
                "http://links.example.invalid/app/dev/inbox",
                "https://links.example.invalid.evil/app/dev/inbox",
                "https://links.example.invalid@evil.invalid/app/dev/inbox",
                "https://user@links.example.invalid/app/dev/inbox",
                "https://links.example.invalid:443/app/dev/inbox",
                "https://links.example.invalid./app/dev/inbox",
                "https://links.example.invalid\\@evil.invalid/app/dev/inbox",
                "https://links.example.invalid/app/prod/inbox",
                "https://links.example.invalid/app/DEV/inbox",
                "https://links.example.invalid/APP/dev/inbox",
                "https://links.example.invalid/app/dev/inbox?next=account",
                "https://links.example.invalid/app/dev/inbox#account",
                "https://links.example.invalid/app/dev/%69nbox",
                "https://links.example.invalid/app/dev/../inbox",
                "https://links.example.invalid/app/dev//inbox",
                "https://links.example.invalid/app/dev/inbox/",
                "https://links.example.invalid/app/dev/",
                "https://links.example.invalid/app/dev/inbox\n",
                "#/inbox?read=true",
                "#/inbox#read",
                "#/inbox\u0000",
                "#/" + "a".repeat(4095),
            )
            .forEach { assertNull(resolve(it), it) }
    }

    @Test
    fun rejectsNonAsciiSchemeAndHostCaseLookalikes() {
        listOf(
                "https://l\u0131nks.example.invalid/app/dev/inbox",
                "https://link\u017f.example.invalid/app/dev/inbox",
                "http\u017f://links.example.invalid/app/dev/inbox",
                AppEnvironment.Dev.linkScheme.replace("s", "\u017f") + "://app/inbox",
            )
            .forEach { assertNull(resolve(it), "Non-ASCII scheme/host must not match") }
    }

    @Test
    fun invalidConfiguredHostsFailBeforeAcceptingExternalInput() {
        listOf(
                "",
                "localhost",
                "127.0.0.1",
                "*.example.com",
                "example.com/",
                "EXAMPLE.com",
                "user@example.com",
                "example.com:443",
                "-x.example.com",
                "x..com",
                "a".repeat(64) + ".com",
                "example.com\n",
            )
            .forEach {
                assertFailsWith<IllegalArgumentException> { AppLinkPath(AppEnvironment.Dev, it) }
            }
    }
}
