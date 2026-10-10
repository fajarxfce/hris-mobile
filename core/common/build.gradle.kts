plugins { id("starter.kmp") }

kotlin.sourceSets { getByName("commonMain").dependencies { api(libs.coroutines.core) } }
