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
                resValue("string", "app_name", "Fluent Starter$suffix")
                manifestPlaceholders["deepLinkScheme"] =
                    if (environment == "prod") "fluentstarter" else "fluentstarter-$environment"
                manifestPlaceholders["appAuthRedirectScheme"] =
                    if (environment == "prod") "fluentstarter" else "fluentstarter-$environment"
                buildConfigField("String", "APP_ENVIRONMENT", "\"$environment\"")
            }
        }
    }
}
