import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Usage

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
