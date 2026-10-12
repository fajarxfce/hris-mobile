package dev.fajar.starter.fileio

/** Syntax/size bounds for an SDK filter, not an application allowlist. */
internal fun validateFileMediaTypes(mediaTypes: Set<String>) {
    require(mediaTypes.size in 1..16)
    require(mediaTypes.all { it.length <= 128 && mediaTypeSyntax.matches(it) })
}

private val mediaTypeSyntax = Regex("[a-zA-Z0-9!#$&^_.+*-]+/[a-zA-Z0-9!#$&^_.+*-]+")
