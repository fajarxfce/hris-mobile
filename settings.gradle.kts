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
    ":core:presentation",
    ":core:network",
    ":core:storage",
    ":core:designsystem",
    ":core:identity:domain",
    ":core:identity:data",
    ":features:onboarding:domain",
    ":features:onboarding:data",
    ":features:onboarding:presentation",
    ":features:auth:presentation",
    ":features:dashboard:domain",
    ":features:dashboard:data",
    ":features:dashboard:presentation",
)
