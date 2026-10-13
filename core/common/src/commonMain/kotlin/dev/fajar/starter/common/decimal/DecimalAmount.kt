package dev.fajar.starter.common.decimal

import kotlin.jvm.JvmInline

/** Exact, bounded decimal representation. This value does not perform floating-point arithmetic. */
@JvmInline
value class DecimalAmount private constructor(val value: String) {
    val isZero: Boolean
        get() = value == "0"

    val isNegative: Boolean
        get() = value.startsWith('-')

    override fun toString(): String = value

    companion object {
        fun parse(value: String): DecimalAmount {
            require(value.length in 1..56 && decimalPattern.matches(value)) {
                "Invalid decimal representation."
            }
            val integral = value.substringBefore('.')
            val fraction = value.substringAfter('.', "").trimEnd('0')
            val normalized = integral + if (fraction.isEmpty()) "" else ".$fraction"
            return DecimalAmount(if (normalized == "-0") "0" else normalized)
        }
    }
}

private val decimalPattern = Regex("-?(0|[1-9][0-9]{0,35})(\\.[0-9]{1,18})?")
