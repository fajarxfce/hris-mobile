package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkIdentityData
import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalDeviceIdentity
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64

/** Matches HRIS's device-key fingerprint; a caller still owns admission and store lifecycle. */
suspend fun CollaborationSdkIdentityData.toLocalDeviceIdentity(): CollaborationLocalDeviceIdentity {
    require(userId.length in 4..320 && userId.startsWith('@'))
    require(userId.indexOf(':') in 2 until userId.lastIndex)
    require(userId.all { it.code in 33..126 })
    require(deviceId.length in 1..255 && deviceId.all { it.code in 33..126 })
    val signing = publicKey(ed25519)
    val curve = publicKey(curve25519)
    val context =
        "org.hris.matrix.device-keys.v1\u0000".encodeToByteArray() +
            userId.encodeToByteArray() +
            byteArrayOf(0) +
            deviceId.encodeToByteArray() +
            byteArrayOf(0) +
            signing +
            curve
    val hash = CryptographyProvider.Default.get(SHA256).hasher().hash(context).toHexString()
    return CollaborationLocalDeviceIdentity(userId, deviceId, hash)
}

private fun publicKey(value: String?): ByteArray {
    val encoded = requireNotNull(value)
    require(encoded.matches(Regex("[A-Za-z0-9+/]{43}=?")))
    val padded = encoded.padEnd(44, '=')
    val decoded = Base64.decode(padded)
    require(decoded.size == 32 && Base64.encode(decoded) == padded)
    return decoded
}
