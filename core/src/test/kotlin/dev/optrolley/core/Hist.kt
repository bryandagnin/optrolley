package dev.optrolley.core

import dev.optrolley.core.Observation.Added
import dev.optrolley.core.Observation.ChainCreated
import dev.optrolley.core.Observation.Edited
import dev.optrolley.core.Observation.NotHere
import dev.optrolley.core.Observation.NotHereUndone
import dev.optrolley.core.Observation.Removed
import dev.optrolley.core.Observation.RouterChosen
import dev.optrolley.core.Observation.Selected
import dev.optrolley.core.Observation.StoreChainSet
import dev.optrolley.core.Observation.StoreCreated
import dev.optrolley.core.Observation.Ticked
import dev.optrolley.core.Observation.TripEnded
import dev.optrolley.core.Observation.Unticked

fun key(name: String) = ItemKey.of(name)

fun keys(vararg names: String) = names.map(::key)

val A = StoreId("a")
val B = StoreId("b")
val C = StoreId("c")
val CHAIN = ChainId("chain")

/** Builds histories with a clock that advances 10 s per action unless told otherwise. */
class Hist {
    val list = ArrayList<Observation>()
    var t = 1_000_000L

    private fun next(gap: Long): Long {
        t += gap
        return t
    }

    fun add(vararg names: String) = apply { names.forEach { list += Added(next(GAP), it) } }

    fun addWith(name: String, quantity: String?, note: String?) = apply { list += Added(next(GAP), name, quantity, note) }

    fun edit(item: String, name: String, quantity: String? = null, note: String? = null) =
        apply { list += Edited(next(GAP), key(item), name, quantity, note) }

    fun remove(name: String) = apply { list += Removed(next(GAP), key(name)) }

    fun tick(name: String, gap: Long = GAP) = apply { list += Ticked(next(gap), key(name)) }

    fun ticks(vararg names: String) = apply { names.forEach { tick(it) } }

    fun untick(name: String) = apply { list += Unticked(next(GAP), key(name)) }

    fun store(id: StoreId, chain: ChainId? = null) = apply { list += StoreCreated(next(GAP), id, id.value, chain) }

    fun chain(id: ChainId) = apply { list += ChainCreated(next(GAP), id, id.value) }

    fun setChain(store: StoreId, chain: ChainId?) = apply { list += StoreChainSet(next(GAP), store, chain) }

    fun select(store: StoreId?) = apply { list += Selected(next(GAP), store) }

    fun end() = apply { list += TripEnded(next(GAP), EndReason.USER) }

    fun notHere(name: String, store: StoreId, scope: Scope = Scope.STORE) =
        apply { list += NotHere(next(GAP), key(name), store, scope) }

    fun undoNotHere(name: String, store: StoreId, scope: Scope = Scope.STORE) =
        apply { list += NotHereUndone(next(GAP), key(name), store, scope) }

    fun choose(router: String) = apply { list += RouterChosen(next(GAP), router) }

    /** One whole trip at [store] buying [names] in that order, without ties. */
    fun trip(store: StoreId, vararg names: String) = apply {
        select(store)
        add(*names)
        ticks(*names)
        end()
    }

    companion object {
        const val GAP = 10_000L
    }
}

fun Presentation.section(kind: SectionKind): List<ItemKey> =
    sections.firstOrNull { it.kind == kind }?.entries?.map { it.item }.orEmpty()

fun Presentation.kinds(): List<SectionKind> = sections.map { it.kind }

fun Presentation.all(): List<ItemKey> = sections.flatMap { s -> s.entries.map { it.item } }
