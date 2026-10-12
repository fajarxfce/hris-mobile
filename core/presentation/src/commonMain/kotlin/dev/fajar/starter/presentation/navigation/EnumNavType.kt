package dev.fajar.starter.presentation.navigation

import androidx.navigation.NavType
import androidx.savedstate.SavedState
import kotlin.reflect.KClass

/** Explicit enum support on every Navigation target. Names are part of the saved-route contract. */
inline fun <reified T : Enum<T>> enumNavType(): NavType<T> =
    NamedEnumNavType(T::class, enumValues<T>().toList())

@PublishedApi
internal class NamedEnumNavType<T : Enum<T>>(
    private val enumClass: KClass<T>,
    private val values: List<T>,
) : NavType<T>(isNullableAllowed = false) {
    override fun put(bundle: SavedState, key: String, value: T) {
        NavType.StringType.put(bundle, key, value.name)
    }

    override fun get(bundle: SavedState, key: String): T? =
        NavType.StringType[bundle, key]?.let(::parseValue)

    override fun parseValue(value: String): T =
        values.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Unknown enum route value")

    override fun serializeAsValue(value: T): String =
        NavType.StringType.serializeAsValue(value.name)

    // Navigation compares argument types when deciding whether to replace the graph and its stack.
    override fun equals(other: Any?): Boolean =
        other is NamedEnumNavType<*> && enumClass == other.enumClass

    override fun hashCode(): Int = enumClass.hashCode()
}
