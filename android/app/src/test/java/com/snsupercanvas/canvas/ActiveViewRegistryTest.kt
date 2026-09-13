package com.snsupercanvas.canvas

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Attach/detach semantics of the injected live-view registry. */
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
}
