package dev.fajar.starter.home.presentation.services

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.home.presentation.catalog.HomeServiceCatalog

data class ServicesState(
    val companyName: String? = null,
    val services: HomeServiceCatalog = HomeServiceCatalog(),
    val loading: Boolean = true,
    val failure: Failure? = null,
)
