plugins { id("starter.kmp") }

kotlin.sourceSets.getByName("commonMain").dependencies { api(projects.core.common) }

kotlin.sourceSets.getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
