package dev.fajar.starter.location.data.di

import dev.fajar.starter.location.domain.repositories.LocationRepository
import dev.fajar.starter.location.domain.usecases.OpenLocationSettings
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.location.data")
class LocationDataModule {
    @Factory fun settings(repository: LocationRepository) = OpenLocationSettings(repository)
}
