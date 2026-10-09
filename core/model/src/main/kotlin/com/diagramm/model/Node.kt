package com.diagramm.model

enum class NodeKind {
    FILE,
    DIRECTORY,

    /** Synthetic: unused capacity of the volume / cloud quota. Never deletable. */
    FREE_SPACE,

    /** Synthetic: used capacity we could not attribute to a file (system data, other services). */
    HIDDEN_SPACE,
}

/**
 * One file or folder of a scanned tree. Trees are immutable once built: sizes of folders are the sum
 * of their children, and [children] are sorted by size, biggest first.
 *
 * A node belongs to exactly one tree. [removing] and [plusChildren] return a *new* tree that re-uses
 * untouched subtrees (and re-parents them), so the tree they were called on must be discarded.
 */
class Node private constructor(
    val id: String,
    val name: String,
    val kind: NodeKind,
    fileSize: Long,
    val modifiedMillis: Long,
    val mimeType: String?,
    /** Content hash if the storage reports one (Drive md5, Yandex md5). */
    val checksum: String?,
    /** Browser link for cloud items. */
    val link: String?,
    /** True for folders we were not allowed to read (e.g. Android/data). */
    val accessDenied: Boolean,
    children: List<Node>,
) {
    val children: List<Node> =
        if (children.isEmpty()) emptyList() else children.sortedByDescending { it.size }

    /** Total bytes: own size for files and synthetic nodes, sum of children for folders. */
    val size: Long = if (kind == NodeKind.DIRECTORY) this.children.sumOf { it.size } else fileSize

    /** Number of files in this subtree (a file counts as 1). */
    val fileCount: Long =
        if (kind == NodeKind.DIRECTORY) this.children.sumOf { it.fileCount } else if (kind == NodeKind.FILE) 1 else 0

    var parent: Node? = null
        private set

    init {
        for (child in this.children) child.parent = this
    }

    val isDirectory: Boolean get() = kind == NodeKind.DIRECTORY
    val isFile: Boolean get() = kind == NodeKind.FILE
    val isSynthetic: Boolean get() = kind == NodeKind.FREE_SPACE || kind == NodeKind.HIDDEN_SPACE

    /** Size without the free-space children, i.e. what is actually stored. */
    val usedSize: Long get() = size - children.filter { it.kind == NodeKind.FREE_SPACE }.sumOf { it.size }

    /** Nodes from the tree root down to this node (inclusive). */
    fun pathFromRoot(): List<Node> {
        val result = ArrayList<Node>()
        var n: Node? = this
        while (n != null) {
            result.add(n)
            n = n.parent
        }
        result.reverse()
        return result
    }

    fun ancestors(): Sequence<Node> = generateSequence(parent) { it.parent }

    fun isDescendantOf(other: Node): Boolean = ancestors().any { it === other || it.id == other.id }

    /** Depth-first traversal including this node. */
    fun walk(): Sequence<Node> = sequence {
        val stack = ArrayDeque<Node>()
        stack.addLast(this@Node)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            yield(n)
            for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
        }
    }

    fun findById(id: String): Node? = walk().firstOrNull { it.id == id }

    /** Copy of this folder with the given children (kept by identity, not copied). */
    fun withChildren(newChildren: List<Node>): Node {
        check(isDirectory) { "Only directories have children" }
        return Node(id, name, kind, 0, modifiedMillis, mimeType, checksum, link, accessDenied, newChildren)
    }

    fun plusChildren(extra: List<Node>): Node = withChildren(children + extra)

    /**
     * New tree without the descendants whose id is in [ids]. Returns `this` when nothing matched.
     * The root itself is never removed.
     */
    fun removing(ids: Set<String>): Node {
        if (ids.isEmpty() || !isDirectory) return this
        var changed = false
        val kept = ArrayList<Node>(children.size)
        for (child in children) {
            if (child.id in ids) {
                changed = true
                continue
            }
            val updated = child.removing(ids)
            if (updated !== child) changed = true
            kept.add(updated)
        }
        return if (changed) withChildren(kept) else this
    }

    override fun toString(): String = "Node($name, $kind, $size)"

    companion object {
        fun file(
            id: String,
            name: String,
            size: Long,
            modifiedMillis: Long = 0,
            mimeType: String? = null,
            checksum: String? = null,
            link: String? = null,
        ) = Node(id, name, NodeKind.FILE, size.coerceAtLeast(0), modifiedMillis, mimeType, checksum, link, false, emptyList())

        fun directory(
            id: String,
            name: String,
            children: List<Node> = emptyList(),
            modifiedMillis: Long = 0,
            link: String? = null,
            accessDenied: Boolean = false,
        ) = Node(id, name, NodeKind.DIRECTORY, 0, modifiedMillis, null, null, link, accessDenied, children)

        fun freeSpace(size: Long, name: String = "Free space") =
            Node("synthetic:free", name, NodeKind.FREE_SPACE, size.coerceAtLeast(0), 0, null, null, null, false, emptyList())

        fun hiddenSpace(size: Long, name: String = "Other data") =
            Node("synthetic:hidden", name, NodeKind.HIDDEN_SPACE, size.coerceAtLeast(0), 0, null, null, null, false, emptyList())
    }
}
