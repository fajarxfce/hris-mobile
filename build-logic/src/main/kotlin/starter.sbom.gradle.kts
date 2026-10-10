import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component

plugins { id("org.cyclonedx.bom") }

// Select the packaged runtime, excluding test/build-tool dependencies from the app inventory.
tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs =
        listOf(
            providers.gradleProperty("sbomConfiguration").getOrElse("prodReleaseRuntimeClasspath")
        )
    projectType = Component.Type.APPLICATION
    componentName = "fluent-starter-${project.name}"
    componentVersion = providers.gradleProperty("appVersion").get()
    jsonOutput = layout.buildDirectory.file("reports/sbom/android.cdx.json")
}
