package dev.fajar.starter.app.navigation

import dev.fajar.starter.common.config.AppEnvironment
import kotlin.test.*

class ResolveAppLinkTest {
    private val resolve = ResolveAppLink(AppEnvironment.Dev)

    @Test
    fun acceptsOnlyKnownRoutesAndTheCurrentEnvironment() {
        assertEquals(AppLink.Inbox, resolve("hrismobile-dev://app/inbox"))
        assertEquals(AppLink.Overview, resolve("#/overview"))
        assertEquals(AppLink.DataSync, resolve("#/data-sync"))
        assertEquals(AppLink.PendingActions, resolve("#/pending-actions"))
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
                "#/activity",
                "#/" + "a".repeat(4096),
            )
            .forEach { assertNull(resolve(it), it) }
    }

    @Test
    fun resourceLinksRequireExactlyThreeCanonicalIdsWithoutQueryOrEncoding() {
        val account = "11111111-1111-4111-8111-111111111111"
        val company = "22222222-2222-4222-8222-222222222222"
        val item = "33333333-3333-4333-8333-333333333333"
        val path = "accounts/$account/companies/$company/inbox/$item"
        val expected = AppLink.InboxItem(account, company, item)
        assertEquals(expected, resolve("hrismobile-dev://app/$path"))
        assertEquals(expected, resolve("#/$path"))
        listOf(
                "$path/",
                "$path?account=$account",
                "$path#inbox",
                path.replace(item, "../account"),
                path.replace(account, "%31" + account.drop(1)),
                path.replace(company, "invalid"),
                path.replace("/inbox/", "/payroll/"),
                path.replace(item, item + "\n"),
                "accounts/$account/inbox/$item",
            )
            .forEach { assertNull(resolve("#/$it"), it) }
    }
}
