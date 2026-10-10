plugins { id("com.android.application") }

android {
    buildFeatures {
        buildConfig = true
        resValues = true
    }
    flavorDimensions += "environment"
    productFlavors {
        listOf("dev", "staging", "prod").forEach { environment ->
            create(environment) {
                dimension = "environment"
                if (environment != "prod") applicationIdSuffix = ".$environment"
                val suffix =
                    if (environment == "prod") ""
                    else " ${environment.replaceFirstChar { it.uppercase() }}"
                resValue("string", "app_name", "HRIS Mobile$suffix")
                manifestPlaceholders["deepLinkScheme"] =
                    if (environment == "prod") "hrismobile" else "hrismobile-$environment"
                manifestPlaceholders["appAuthRedirectScheme"] =
                    if (environment == "prod") "hrismobile" else "hrismobile-$environment"
                buildConfigField("String", "APP_ENVIRONMENT", "\"$environment\"")
            }
        }
    }
}
