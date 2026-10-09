package com.diagramm.model

/**
 * The DaisyDisk "collector": a basket of nodes the user wants to delete. Immutable, so it can live in
 * a StateFlow. Nesting is resolved automatically: adding a folder drops its already-collected
 * descendants, and adding something inside a collected folder is a no-op, so [totalSize] never counts
 * a byte twice.
 */
class Collector private constructor(val items: List<Node>) {
    constructor() : this(emptyList())

    val isEmpty: Boolean get() = items.isEmpty()
    val totalSize: Long get() = items.sumOf { it.size }
    val ids: Set<String> get() = items.mapTo(HashSet()) { it.id }

    /** True if [node] itself or one of its ancestors is collected. */
    fun covers(node: Node): Boolean =
        items.any { it.id == node.id } || node.ancestors().any { a -> items.any { it.id == a.id } }

    fun contains(node: Node): Boolean = items.any { it.id == node.id }

    fun plus(node: Node): Collector {
        if (node.isSynthetic || covers(node)) return this
        val remaining = items.filterNot { it.isDescendantOf(node) }
        return Collector(remaining + node)
    }

    fun minus(node: Node): Collector {
        val remaining = items.filterNot { it.id == node.id }
        return if (remaining.size == items.size) this else Collector(remaining)
    }

    fun toggle(node: Node): Collector = if (contains(node)) minus(node) else plus(node)

    fun minusIds(ids: Set<String>): Collector {
        val remaining = items.filterNot { it.id in ids }
        return if (remaining.size == items.size) this else Collector(remaining)
    }

    fun cleared(): Collector = if (items.isEmpty()) this else Collector(emptyList())
}
