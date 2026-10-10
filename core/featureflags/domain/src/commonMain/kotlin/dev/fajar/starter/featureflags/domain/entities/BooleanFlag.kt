package dev.fajar.starter.featureflags.domain.entities

/** Define flags in the domain module of the feature that owns their behavior. */
data class BooleanFlag(val key: String, val defaultValue: Boolean) {
    init {
        require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,255}"))) { "Invalid flag key." }
    }
}
