import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("starter.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val commonMain = kotlin.sourceSets.getByName("commonMain")
val sqliteMain =
    kotlin.sourceSets.create("sqliteMain") {
        dependsOn(commonMain)
        dependencies {
            implementation(catalog.findLibrary("room-runtime").get())
            implementation(catalog.findLibrary("sqlite-bundled").get())
        }
    }

listOf("androidMain", "desktopMain", "iosMain").forEach {
    kotlin.sourceSets.getByName(it).dependsOn(sqliteMain)
}

dependencies {
    listOf("kspAndroid", "kspDesktop", "kspIosArm64", "kspIosSimulatorArm64").forEach {
        add(it, catalog.findLibrary("room-compiler").get())
    }
}

room { schemaDirectory(layout.projectDirectory.dir("schemas").asFile.path) }
