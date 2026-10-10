pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "Binaryen distributions"
            patternLayout {
                artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "compose-fluent-starter"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":apps:android",
    ":apps:shared",
    ":apps:demo",
    ":core:common",
    ":core:security:domain",
    ":core:availability:domain",
    ":core:availability:data",
    ":features:availability:presentation",
    ":features:security:presentation",
    ":core:security:data",
    ":core:presentation",
    ":core:network",
    ":core:storage",
    ":core:securestorage",
    ":core:observability",
    ":features:settings:presentation",
    ":core:localization",
    ":core:settings:data",
    ":core:settings:domain",
    ":core:datastore",
    ":core:database",
    ":core:transfers:domain",
    ":core:transfers:data",
    ":features:files:presentation",
    ":core:sync:domain",
    ":core:sync:data",
    ":core:worker",
    ":core:featureflags:domain",
    ":core:featureflags:data",
    ":core:notifications:domain",
    ":core:notifications:data",
    ":core:designsystem",
    ":core:identity:domain",
    ":core:identity:data",
    ":features:onboarding:domain",
    ":features:onboarding:data",
    ":features:onboarding:presentation",
    ":features:auth:presentation",
    ":features:notifications:presentation",
    ":features:dashboard:domain",
    ":features:dashboard:data",
    ":features:dashboard:presentation",
)
