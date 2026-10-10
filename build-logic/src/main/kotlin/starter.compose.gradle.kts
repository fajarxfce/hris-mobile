import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("starter.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin.sourceSets.getByName("commonMain").dependencies {
    implementation(catalog.findLibrary("compose-runtime").get())
    implementation(catalog.findLibrary("compose-foundation").get())
    implementation(catalog.findLibrary("compose-ui").get())
    implementation(catalog.findLibrary("compose-resources").get())
}
