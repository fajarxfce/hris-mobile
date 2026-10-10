package dev.fajar.starter.app.navigation

import dev.fajar.starter.common.config.AppEnvironment
import kotlin.test.*

class ResolveAppLinkTest {
    private val resolve = ResolveAppLink(AppEnvironment.Dev)

    @Test
    fun acceptsOnlyKnownRoutesAndTheCurrentEnvironment() {
        assertEquals(AppLink.Inbox, resolve("hrismobile-dev://app/inbox"))
        assertEquals(AppLink.Activity, resolve("#/activity"))
        assertEquals(AppLink.Account, resolve("HRISMOBILE-DEV://APP/account"))
        listOf(
                "fluentstarter-dev://app/account",
                "hrismobile://app/inbox",
                "hrismobile-staging://app/inbox",
                "hrismobile-dev://evil/inbox",
                "https://example.com/#/inbox",
                "hrismobile-dev://app/account?redirect=evil",
                "#/../account",
                "#/%61ccount",
                "#/inbox/unknown",
                "#/" + "a".repeat(4096),
            )
            .forEach { assertNull(resolve(it), it) }
    }
}
