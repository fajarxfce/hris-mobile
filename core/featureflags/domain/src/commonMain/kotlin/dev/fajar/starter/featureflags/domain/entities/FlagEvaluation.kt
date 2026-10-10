package dev.fajar.starter.featureflags.domain.entities

data class FlagEvaluation(val enabled: Boolean, val source: FlagSource)

enum class FlagSource {
    Default,
    Remote,
    Override,
}
