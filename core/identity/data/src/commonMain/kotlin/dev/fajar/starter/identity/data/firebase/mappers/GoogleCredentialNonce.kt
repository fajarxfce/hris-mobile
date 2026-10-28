@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.fajar.starter.identity.data.firebase.mappers

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*

/** Binds a platform callback to its request. Firebase still verifies the provider signature. */
fun requireGoogleCredentialNonce(idToken: String, clientId: String, nonce: String) {
    require(idToken.length in 32..8192)
    val parts = idToken.split('.')
    require(
        parts.size == 3 &&
            parts.all {
                it.isNotEmpty() &&
                    it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' }
            }
    )
    val claims =
        Json.parseToJsonElement(
                Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
                    .decode(parts[1])
                    .decodeToString()
            )
            .jsonObject
    val actualNonce = claims["nonce"]?.jsonPrimitive
    val audience = claims["aud"]?.jsonPrimitive
    require(actualNonce?.isString == true && audience?.isString == true)
    require(actualNonce.content == nonce && audience.content == clientId)
}
