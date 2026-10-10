package dev.fajar.starter.home.presentation.home

sealed interface HomeEffect {
    data object OpenSettings : HomeEffect

    data object OpenProfile : HomeEffect
}
