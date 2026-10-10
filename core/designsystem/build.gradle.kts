plugins { id("starter.compose") }

kotlin.sourceSets { getByName("commonMain").dependencies { api(libs.fluent) } }
