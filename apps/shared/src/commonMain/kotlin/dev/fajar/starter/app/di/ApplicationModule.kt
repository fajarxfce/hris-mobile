package dev.fajar.starter.app.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module
@ComponentScan("dev.fajar.starter.app")
class ApplicationModule {
    @org.koin.core.annotation.Factory
    fun links(environment: dev.fajar.starter.common.config.AppEnvironment) =
        dev.fajar.starter.app.navigation.ResolveAppLink(environment)
}
