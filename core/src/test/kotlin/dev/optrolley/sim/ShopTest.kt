package dev.optrolley.sim

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ShopTest {
    private val spec = ShopSpec("test", zones = 10, items = 80, staples = 15)

    @Test
    fun `a shop has its zones and items, none empty or shared`() {
        for (seed in 1L..20L) {
            val shop = Shop.generate(spec, Random(seed))
            assertEquals(spec.zones, shop.layout.zones.size)
            assertTrue(shop.layout.zones.all { it.isNotEmpty() })
            assertEquals(spec.items, shop.layout.zones.sumOf { it.size })
            assertEquals(spec.items, shop.layout.items.size)
            assertEquals(Shop.UNSTOCKED, shop.unstocked.size)
            assertTrue(shop.unstocked.none { it in shop.layout })
            assertEquals(spec.items + Shop.UNSTOCKED, shop.names.size)
        }
    }

    @Test
    fun `names say nothing about the layout`() {
        // Sorting by name should be about as disordered as paper: half the cross-zone pairs.
        val shares = (1L..20L).map { seed ->
            val layout = Shop.generate(spec, Random(seed)).layout
            val byName = layout.items.sortedBy { it.value }
            val walk = layout.zones.map { it.toSet() }
            Measure.presented(walk, byName).normalised
        }
        val mean = shares.average()
        assertTrue(mean in 0.4..0.6, "name order matches layout too well or too badly: $mean")
    }

    @Test
    fun `the same seed gives the same shop`() {
        assertEquals(Shop.generate(spec, Random(7)).layout, Shop.generate(spec, Random(7)).layout)
        assertNotEquals(Shop.generate(spec, Random(7)).layout, Shop.generate(spec, Random(8)).layout)
    }

    @Test
    fun `a rearrangement moves zones and items, and keeps every item`() {
        for (seed in 1L..20L) {
            val layout = Shop.generate(spec, Random(seed)).layout
            val moved = layout.rearranged(Random(seed), zoneShare = 0.3, itemShare = 0.1)
            assertNotEquals(layout, moved)
            assertEquals(layout.items, moved.items)
            assertEquals(spec.items, moved.zones.sumOf { it.size })
            assertTrue(moved.zones.all { it.isNotEmpty() })
            val itemsMoved = layout.items.count { a ->
                layout.items.any { b -> (layout.zoneOf(a) == layout.zoneOf(b)) != (moved.zoneOf(a) == moved.zoneOf(b)) }
            }
            assertTrue(itemsMoved > 0)
            val zoneOrderChanged = layout.items.any { a ->
                layout.items.any { b -> layout.zoneOf(a) < layout.zoneOf(b) && moved.zoneOf(a) > moved.zoneOf(b) }
            }
            assertTrue(zoneOrderChanged)
        }
    }

    @Test
    fun `layout equality ignores order within a zone`() {
        val a = Shop.generate(spec, Random(1)).layout
        val b = Layout(a.zones.map { it.reversed() })
        assertEquals(a, b)
    }
}
