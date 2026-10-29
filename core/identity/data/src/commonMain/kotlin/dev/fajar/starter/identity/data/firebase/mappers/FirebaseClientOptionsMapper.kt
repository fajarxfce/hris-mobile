package dev.fajar.starter.identity.data.firebase.mappers

import dev.fajar.starter.identity.data.firebase.dto.FirebaseClientOptions

fun FirebaseClientOptions.validated(): FirebaseClientOptions {
    require(projectId.matches(Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]")))
    require(apiKey.matches(Regex("[A-Za-z0-9_-]{20,128}")))
    require(
        googleWebClientId == null ||
            googleWebClientId.matches(Regex("[0-9]+-[a-z0-9]+\\.apps\\.googleusercontent\\.com"))
    )
    require((androidPackage == null) == (androidCertificate == null))
    require(androidPackage == null || androidPackage.matches(Regex("[A-Za-z][A-Za-z0-9_.]{1,254}")))
    require(androidCertificate == null || androidCertificate.matches(Regex("[A-F0-9]{40}")))
    require(iosBundleId == null || iosBundleId.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{1,254}")))
    require(iosBundleId == null || androidPackage == null)
    return this
}
