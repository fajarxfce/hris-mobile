package dev.fajar.starter.demo

import dev.fajar.starter.common.config.*

fun demoOidcClients() =
    listOf(
        OidcClientSettings(
            "demo",
            "Demo organization",
            "https://demo.fluent.local",
            AppPlatform.entries.associateWith {
                OidcPlatformSettings(
                    "demo-public-client",
                    "https://demo.fluent.local/oauth/callback",
                )
            },
        )
    )

internal const val DEMO_OIDC_DISCOVERY =
    """{
  "issuer":"https://demo.fluent.local",
  "authorization_endpoint":"https://demo.fluent.local/authorize",
  "token_endpoint":"https://demo.fluent.local/token",
  "jwks_uri":"https://demo.fluent.local/jwks",
  "response_types_supported":["code"],
  "subject_types_supported":["public"],
  "id_token_signing_alg_values_supported":["RS256"],
  "token_endpoint_auth_methods_supported":["none"],
  "code_challenge_methods_supported":["S256"],
  "scopes_supported":["openid","profile","email"]
}"""
