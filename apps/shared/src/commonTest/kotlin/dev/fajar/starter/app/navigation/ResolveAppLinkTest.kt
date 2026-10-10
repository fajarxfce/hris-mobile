package dev.fajar.starter.app.navigation

import dev.fajar.starter.common.config.AppEnvironment
import kotlin.test.*

class ResolveAppLinkTest {
    private val resolve = ResolveAppLink(AppEnvironment.Dev)

    @Test
    fun acceptsOnlyKnownRoutesAndTheCurrentEnvironment() {
        assertEquals(AppLink.Inbox, resolve("fluentstarter-dev://app/inbox"))
        assertEquals(AppLink.Activity, resolve("#/activity"))
        assertEquals(AppLink.Account, resolve("FLUENTSTARTER-DEV://APP/account"))
        listOf(
                "fluentstarter://app/inbox",
                "fluentstarter-staging://app/inbox",
                "fluentstarter-dev://evil/inbox",
                "https://example.com/#/inbox",
                "fluentstarter-dev://app/account?redirect=evil",
                "#/../account",
                "#/%61ccount",
                "#/inbox/unknown",
                "#/" + "a".repeat(4096),
            )
            .forEach { assertNull(resolve(it), it) }
    }
}
