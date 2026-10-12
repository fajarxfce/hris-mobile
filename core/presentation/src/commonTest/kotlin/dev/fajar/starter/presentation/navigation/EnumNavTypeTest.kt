package dev.fajar.starter.presentation.navigation

import androidx.navigation.NavArgument
import androidx.navigation.NavType
import androidx.savedstate.savedState
import kotlin.test.*

class EnumNavTypeTest {
    private enum class Mode {
        VIEW,
        EDIT,
    }

    private enum class OtherMode {
        VIEW,
        EDIT,
    }

    @Test
    fun rebuildingAnArgumentPreservesItsGraphIdentityWithoutMixingEnumTypes() {
        val first = enumNavType<Mode>()
        val next = enumNavType<Mode>()
        assertNotSame(first, next)
        assertEquals(first, next)
        assertEquals(first.hashCode(), next.hashCode())
        assertEquals(
            NavArgument.Builder().setType(first).build(),
            NavArgument.Builder().setType(next).build(),
        )
        assertNotEquals<NavType<*>>(first, enumNavType<OtherMode>())
    }

    @Test
    fun eachRouteValueSurvivesSavedStateWithoutReflectionOrAnOrdinal() {
        val type = enumNavType<Mode>()
        val saved = savedState()
        assertNull(type[saved, "mode"])
        assertFalse(type.isNullableAllowed)
        for (mode in Mode.entries) {
            type.put(saved, "mode", mode)
            assertEquals(mode, type[saved, "mode"])
            assertEquals(mode.name, NavType.StringType[saved, "mode"])
            assertEquals(mode, type.parseValue(type.serializeAsValue(mode)))
        }
    }

    @Test
    fun unknownRestoredOrDecodedValuesCannotSelectADifferentAction() {
        val type = enumNavType<Mode>()
        assertFailsWith<IllegalArgumentException> { type.parseValue("edit") }
        assertFailsWith<IllegalArgumentException> { type.parseValue("DELETE") }
        val saved = savedState()
        NavType.StringType.put(saved, "mode", "DELETE")
        assertFailsWith<IllegalArgumentException> { type[saved, "mode"] }
    }
}
