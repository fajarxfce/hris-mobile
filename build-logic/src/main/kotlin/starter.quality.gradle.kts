import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins { base }

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val formatter by
    configurations.creating {
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }

dependencies { add(formatter.name, catalog.findLibrary("ktfmt").get()) }

val sources =
    fileTree(rootDir) {
        include("**/*.kt", "**/*.kts")
        exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**")
    }

tasks.register<JavaExec>("formatKotlin") {
    group = "formatting"
    classpath = formatter
    mainClass.set("com.facebook.ktfmt.cli.Main")
    maxHeapSize = "512m"
    doFirst { setArgs(listOf("--kotlinlang-style") + sources.files.map { it.absolutePath }) }
}

tasks.register<JavaExec>("formatCheck") {
    group = "verification"
    classpath = formatter
    mainClass.set("com.facebook.ktfmt.cli.Main")
    maxHeapSize = "512m"
    doFirst {
        setArgs(
            listOf("--kotlinlang-style", "--dry-run", "--set-exit-if-changed") +
                sources.files.map { it.absolutePath }
        )
    }
}

val staticAnalysis by configurations.creating

dependencies { add(staticAnalysis.name, catalog.findLibrary("detekt-cli").get()) }

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Runs Kotlin static analysis with the workspace rule set."
    classpath = staticAnalysis
    mainClass.set("dev.detekt.cli.Main")
    maxHeapSize = "1g"
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("config/detekt.yml")
    outputs.files("build/reports/detekt/detekt.sarif", "build/reports/detekt/detekt.html")
    doFirst {
        // Analyze manual source trees without traversing generated output or validation caches.
        // Grouping by src keeps the command line below platform length limits.
        val roots =
            sources.files
                .map { source ->
                    val relative = source.relativeTo(rootDir).invariantSeparatorsPath
                    val module = relative.substringBefore("/src/", missingDelimiterValue = "")
                    if (module.isEmpty()) source else file("$module/src")
                }
                .distinct()
                .sortedBy { it.path }
        setArgs(
            listOf(
                "--fail-on-severity",
                "Warning",
                "--input",
                roots.joinToString(java.io.File.pathSeparator) { it.absolutePath },
                "--excludes",
                "**/build/**",
                "**/composeResources/**",
                "**/.gradle/**",
                "**/.kotlin/**",
                "--config",
                "config/detekt.yml",
                "--report",
                "sarif:build/reports/detekt/detekt.sarif",
                "--report",
                "html:build/reports/detekt/detekt.html",
            )
        )
    }
}

tasks.named("check") { dependsOn("detekt") }

// Maintainer-only resolution: include other desktop runtimes when reviewing new checksums.
val desktopRuntimes by
    configurations.creating {
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
        }
    }

dependencies {
    listOf("compose-desktop-windows", "compose-desktop-macos-arm64", "compose-desktop-macos-x64")
        .forEach { add(desktopRuntimes.name, catalog.findLibrary(it).get()) }
}

tasks.register("resolvePlatformArtifacts") {
    group = "verification"
    description =
        "Resolves desktop runtimes and Apple resources for dependency-metadata maintenance."
    dependsOn(
        ":apps:shared:iosArm64ResolveResourcesFromDependencies",
        ":apps:shared:iosSimulatorArm64ResolveResourcesFromDependencies",
    )
    doLast { desktopRuntimes.files }
}
