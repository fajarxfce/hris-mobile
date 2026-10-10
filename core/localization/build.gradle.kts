plugins { id("starter.compose") }

compose.resources {
    publicResClass = true
    packageOfResClass = "dev.fajar.starter.localization.resources"
}

kotlin.sourceSets {
    getByName("commonMain").dependencies { api(projects.core.settings.domain) }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}

kotlin {
    sourceSets.getByName("desktopTest").dependencies { implementation(compose.desktop.currentOs) }
}
