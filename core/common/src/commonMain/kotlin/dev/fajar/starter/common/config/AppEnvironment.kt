package dev.fajar.starter.common.config

enum class AppEnvironment(val id: String, val linkScheme: String) {
    Dev("dev", "hrismobile-dev"),
    Staging("staging", "hrismobile-staging"),
    Prod("prod", "hrismobile"),
}
