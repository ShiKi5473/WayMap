package com.waymap.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffTest {

    private data class Item(val id: String, val v: Int)

    private fun current(vararg items: Item) = LinkedHashMap<String, Item>().apply {
        items.forEach { put(it.id, it) }
    }

    @Test
    fun unchangedListProducesNoWork() {
        val d = diffById(current(Item("a", 1), Item("b", 2)), listOf(Item("a", 1), Item("b", 2))) { it.id }
        assertTrue(d.added.isEmpty())
        assertTrue(d.changed.isEmpty())
        assertTrue(d.removed.isEmpty())
    }

    @Test
    fun classifiesAddedChangedAndRemoved() {
        val d = diffById(
            current(Item("a", 1), Item("b", 2), Item("c", 3)),
            listOf(Item("b", 20), Item("c", 3), Item("d", 4)),
        ) { it.id }
        assertEquals(listOf(Item("d", 4)), d.added)
        assertEquals(listOf(Item("b", 2) to Item("b", 20)), d.changed)
        assertEquals(listOf("a"), d.removed)
    }

    @Test
    fun emptyNextRemovesEverything() {
        val d = diffById(current(Item("a", 1), Item("b", 2)), emptyList()) { it.id }
        assertEquals(listOf("a", "b"), d.removed)
    }

    @Test
    fun duplicateIdsInNextKeepTheLastOne() {
        val d = diffById(current(), listOf(Item("a", 1), Item("a", 2))) { it.id }
        assertEquals(listOf(Item("a", 2)), d.added)
    }
}
