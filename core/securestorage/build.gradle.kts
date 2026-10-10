plugins { id("starter.android.library") }

kotlin.sourceSets {
    getByName("commonMain").dependencies { implementation(libs.coroutines.core) }
    getByName("desktopMain").dependencies { implementation(libs.java.keyring) }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
