package dev.fajar.starter.common.config

enum class AppEnvironment(val id: String, val linkScheme: String) {
    Dev("dev", "fluentstarter-dev"),
    Staging("staging", "fluentstarter-staging"),
    Prod("prod", "fluentstarter"),
}
