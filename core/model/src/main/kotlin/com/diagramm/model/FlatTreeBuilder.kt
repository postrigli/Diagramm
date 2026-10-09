package com.diagramm.model

/** A file or folder as cloud APIs report it: flat, with a parent reference. */
data class FlatEntry(
    val id: String,
    val name: String,
    val parentId: String?,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modifiedMillis: Long = 0,
    val mimeType: String? = null,
    val checksum: String? = null,
    val link: String? = null,
)

object FlatTreeBuilder {
    /**
     * Builds a tree from flat [entries]. Entries whose parent is unknown (or who sit in a cycle's
     * orphaned part) are attached to the root, so no bytes are lost; entries that are part of a pure
     * cycle are unreachable and dropped.
     */
    fun build(rootId: String, rootName: String, entries: Collection<FlatEntry>): Node {
        val byId = LinkedHashMap<String, FlatEntry>(entries.size)
        for (e in entries) if (e.id != rootId) byId[e.id] = e

        val childrenOf = HashMap<String, MutableList<FlatEntry>>()
        for (e in byId.values) {
            val parent = e.parentId?.takeIf { it == rootId || (it in byId && it != e.id) } ?: rootId
            childrenOf.getOrPut(parent) { ArrayList() }.add(e)
        }

        val visited = HashSet<String>()
        fun buildNode(e: FlatEntry): Node {
            if (!e.isDirectory) {
                return Node.file(e.id, e.name, e.size, e.modifiedMillis, e.mimeType, e.checksum, e.link)
            }
            val kids = ArrayList<Node>()
            for (c in childrenOf[e.id].orEmpty()) {
                if (visited.add(c.id)) kids.add(buildNode(c))
            }
            return Node.directory(e.id, e.name, kids, e.modifiedMillis, e.link)
        }

        val rootChildren = ArrayList<Node>()
        for (c in childrenOf[rootId].orEmpty()) {
            if (visited.add(c.id)) rootChildren.add(buildNode(c))
        }
        return Node.directory(rootId, rootName, rootChildren)
    }
}
