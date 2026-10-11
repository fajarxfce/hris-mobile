plugins { id("starter.android.library") }

val commonMain = kotlin.sourceSets.getByName("commonMain")
val jvmSharedMain = kotlin.sourceSets.create("jvmSharedMain") { dependsOn(commonMain) }

listOf("androidMain", "desktopMain").forEach {
    kotlin.sourceSets.getByName(it).dependsOn(jvmSharedMain)
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(libs.coroutines.core)
        implementation(libs.cryptography.optimal)
    }
    getByName("desktopMain").dependencies { implementation(libs.java.keyring) }
    getByName("commonTest").dependencies {
        implementation(libs.coroutines.test)
        implementation(projects.core.common)
        implementation(projects.core.storage)
    }
}
