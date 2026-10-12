plugins { id("starter.android.library") }

val commonMain = kotlin.sourceSets.getByName("commonMain")
val jvmSharedMain = kotlin.sourceSets.create("jvmSharedMain") { dependsOn(commonMain) }

listOf("androidMain", "desktopMain").forEach {
    kotlin.sourceSets.getByName(it).dependsOn(jvmSharedMain)
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(projects.core.common)
        implementation(projects.core.observability)
        implementation(libs.coroutines.core)
        implementation(libs.okio)
    }
    getByName("androidMain").dependencies { implementation(libs.android.activity) }
    getByName("desktopMain").dependencies { implementation(libs.coroutines.swing) }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
