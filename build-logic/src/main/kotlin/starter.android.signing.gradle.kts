plugins { id("com.android.application") }

// Read credentials from the runner environment; never persist them in Gradle properties.
val signingKeys =
    listOf(
        "ANDROID_KEYSTORE_FILE",
        "ANDROID_KEYSTORE_PASSWORD",
        "ANDROID_KEY_ALIAS",
        "ANDROID_KEY_PASSWORD",
    )
val signingValues = signingKeys.associateWith { providers.environmentVariable(it).orNull }
val signingRequested =
    providers.gradleProperty("requireSigning").map(String::toBoolean).getOrElse(false)
val signingSupplied = signingValues.values.any { it != null }

if (signingRequested || signingSupplied) {
    require(signingValues.values.all { !it.isNullOrBlank() }) {
        "All four Android signing environment variables are required."
    }
    val keyFile = file(requireNotNull(signingValues["ANDROID_KEYSTORE_FILE"]))
    require(keyFile.isFile) { "The Android signing keystore is missing." }
    android {
        signingConfigs.create("privateRelease") {
            storeFile = keyFile
            storePassword = signingValues["ANDROID_KEYSTORE_PASSWORD"]
            keyAlias = signingValues["ANDROID_KEY_ALIAS"]
            keyPassword = signingValues["ANDROID_KEY_PASSWORD"]
        }
        buildTypes.named("release") { signingConfig = signingConfigs.getByName("privateRelease") }
    }
}
