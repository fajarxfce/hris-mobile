plugins {
    id("starter.android.library")
    id("starter.serialization")
    id("starter.di")
}

kotlin { android { withHostTest {} } }

val commonMain = kotlin.sourceSets.getByName("commonMain")
val jvmSharedMain = kotlin.sourceSets.create("jvmSharedMain") { dependsOn(commonMain) }

listOf("androidMain", "desktopMain").forEach {
    kotlin.sourceSets.getByName(it).dependsOn(jvmSharedMain)
}

kotlin.sourceSets {
    getByName("androidMain").dependencies {
        implementation(libs.matrix.sdk.android)
        implementation(projects.core.observability)
    }
    getByName("commonMain").dependencies {
        api(projects.features.collaboration.domain)
        implementation(projects.core.network)
        implementation(projects.core.storage)
        implementation(projects.core.securestorage)
        implementation(projects.core.database)
        implementation(libs.serialization.json)
        implementation(libs.cryptography.optimal)
    }
    getByName("androidHostTest").dependencies { implementation(libs.junit) }
    getByName("commonTest").dependencies {
        implementation(libs.coroutines.test)
        implementation(libs.ktor.mock)
    }
}
