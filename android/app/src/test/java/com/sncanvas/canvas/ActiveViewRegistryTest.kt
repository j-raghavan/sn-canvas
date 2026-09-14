package com.sncanvas.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Attach/detach semantics of the injected live-view registry, and work that waits for a view. */
class ActiveViewRegistryTest {
    private val first = Any()
    private val second = Any()

    @Test
    fun `is empty until a view attaches`() {
        assertNull(ActiveViewRegistry<Any>().current())
    }

    @Test
    fun `current is the most recently attached view`() {
        val registry = ActiveViewRegistry<Any>()
        registry.attach(first)
        registry.attach(second)
        assertSame(second, registry.current())
    }

    @Test
    fun `detaching the active view clears it`() {
        val registry = ActiveViewRegistry<Any>()
        registry.attach(first)
        registry.detach(first)
        assertNull(registry.current())
    }

    @Test
    fun `a late detach of a replaced view does not clear its successor`() {
        val registry = ActiveViewRegistry<Any>()
        registry.attach(first)
        registry.attach(second)
        registry.detach(first)
        assertSame(second, registry.current())
    }

    @Test
    fun `work for the live view runs on it at once`() {
        val registry = ActiveViewRegistry<Any>()
        val ran = mutableListOf<Any>()
        registry.attach(first)
        registry.whenAttached { ran += it }
        assertEquals(listOf(first), ran)
    }

    @Test
    fun `work asked for while no view is attached waits for the next view, runs in order, and runs once`() {
        val registry = ActiveViewRegistry<Any>()
        val ran = mutableListOf<String>()
        registry.attach(first)
        registry.detach(first)
        registry.whenAttached { ran += "insert on ${if (it === second) "second" else "other"}" }
        registry.whenAttached { ran += "then select" }
        assertEquals(emptyList<String>(), ran)
        registry.attach(second)
        assertEquals(listOf("insert on second", "then select"), ran)
        registry.attach(first)
        assertEquals(2, ran.size)
    }
}
