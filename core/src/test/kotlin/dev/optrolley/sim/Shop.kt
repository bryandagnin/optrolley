package dev.optrolley.sim

import dev.optrolley.core.ItemKey
import dev.optrolley.core.StoreId
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A shop's true layout: zones in walking order. The order of items inside a
 * zone is not a fact about the shop (tier 1 §5), so nothing may rely on how a
 * zone's list happens to be ordered.
 */
class Layout(val zones: List<List<ItemKey>>) {
    private val zoneIndex: Map<ItemKey, Int> =
        buildMap { zones.forEachIndexed { z, items -> items.forEach { put(it, z) } } }

    val items: Set<ItemKey> get() = zoneIndex.keys

    operator fun contains(item: ItemKey): Boolean = item in zoneIndex

    fun zoneOf(item: ItemKey): Int = zoneIndex.getValue(item)

    /** Zones as sets, which is all a layout says. */
    val shape: List<Set<ItemKey>> get() = zones.map { it.toSet() }

    /**
     * The shop rearranged in pieces (tier 1 §5): [zoneShare] of the zones
     * moved to new places in the walk, and [itemShare] of the items moved to
     * a different zone. Always differs from this layout.
     */
    fun rearranged(random: Random, zoneShare: Double, itemShare: Double): Layout {
        while (true) {
            val zs = zones.map { it.toMutableList() }.toMutableList()
            repeat(max(1, (zoneShare * zs.size).roundToInt())) {
                val from = random.nextInt(zs.size)
                val zone = zs.removeAt(from)
                var to = random.nextInt(zs.size)
                if (to >= from) to++
                zs.add(to, zone)
            }
            val all = zs.flatten()
            repeat(max(1, (itemShare * all.size).roundToInt())) {
                val item = all[random.nextInt(all.size)]
                val from = zs.indexOfFirst { item in it }
                zs[from].remove(item)
                var to = random.nextInt(zs.size - 1)
                if (to >= from) to++
                zs[to].add(random.nextInt(zs[to].size + 1), item)
            }
            val result = Layout(zs.filter { it.isNotEmpty() })
            if (result.shape != shape) return result
        }
    }

    override fun equals(other: Any?): Boolean = other is Layout && other.shape == shape
    override fun hashCode(): Int = shape.hashCode()
    override fun toString(): String = zones.joinToString(" | ") { z -> z.joinToString(",") { it.value } }
}

/**
 * [items] is the shopper's repertoire at this shop: anything stocked that
 * they never buy cannot affect a measurement. [staples] of them are bought
 * most trips, the rest now and then.
 */
data class ShopSpec(val name: String, val zones: Int, val items: Int, val staples: Int)

class Shop(
    val spec: ShopSpec,
    val store: StoreId,
    val layout: Layout,
    /** Wanted by the shopper but not stocked; used only by the absent-item noise. */
    val unstocked: List<ItemKey>,
    val names: Map<ItemKey, String>,
) {
    companion object {
        const val UNSTOCKED = 5

        fun generate(spec: ShopSpec, random: Random): Shop {
            require(spec.items >= spec.zones && spec.zones >= 2)
            // Names are drawn from a shuffled numbering so that no order of
            // names says anything about the layout.
            val numbers = (0 until spec.items + UNSTOCKED).shuffled(random)
            val names = numbers.map { "item-%03d".format(it) }
            val keys = names.map(ItemKey::of)
            val stocked = keys.take(spec.items)
            val zones = List(spec.zones) { mutableListOf<ItemKey>() }
            stocked.forEachIndexed { i, item ->
                val z = if (i < spec.zones) i else random.nextInt(spec.zones)
                zones[z].add(item)
            }
            return Shop(
                spec = spec,
                store = StoreId(spec.name),
                layout = Layout(zones),
                unstocked = keys.drop(spec.items),
                names = keys.zip(names).toMap(),
            )
        }
    }
}
