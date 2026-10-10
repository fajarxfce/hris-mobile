plugins { id("starter.environment") }

kotlin.sourceSets { getByName("commonMain").dependencies { api(libs.coroutines.core) } }
