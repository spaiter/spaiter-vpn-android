package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSelectionPolicyTest {
    @Test
    fun allPreservesOtherGroups() {
        assertEquals(
            setOf(1L, 2L, 3L),
            updateProfileSelection(setOf(1L), listOf(2L, 3L), ProfileSelectionOperation.All),
        )
    }

    @Test
    fun noneOnlyClearsCurrentGroup() {
        assertEquals(
            setOf(1L),
            updateProfileSelection(setOf(1L, 2L, 3L), listOf(2L, 3L), ProfileSelectionOperation.None),
        )
    }

    @Test
    fun invertOnlyTogglesCurrentGroup() {
        assertEquals(
            setOf(1L, 3L),
            updateProfileSelection(setOf(1L, 2L), listOf(2L, 3L), ProfileSelectionOperation.Invert),
        )
    }
}
