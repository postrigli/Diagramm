package com.diagramm.model

/** A file addressed by its full path, as Yandex Disk's flat file listing reports it. */
data class PathEntry(
    /** Slash separated path relative to the root, e.g. `Photos/2024/a.jpg`. */
    val path: String,
    val size: Long,
    val modifiedMillis: Long = 0,
    val mimeType: String? = null,
    val checksum: String? = null,
    val link: String? = null,
)

object PathTreeBuilder {
    private class Dir(val id: String, val name: String) {
        val dirs = LinkedHashMap<String, Dir>()
        val files = ArrayList<Node>()
    }

    /**
     * Builds a tree from a flat list of file paths, creating the folders implicitly.
     * Node ids are `rootId` + relative path (so with rootId `disk:/` they equal Yandex resource paths).
     */
    fun build(rootId: String, rootName: String, entries: Collection<PathEntry>): Node {
        val prefix = if (rootId.endsWith("/")) rootId else "$rootId/"
        val root = Dir(rootId, rootName)
        for (e in entries) {
            val segments = e.path.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue
            var dir = root
            var idSoFar = prefix.dropLast(1)
            for (seg in segments.dropLast(1)) {
                idSoFar = "$idSoFar/$seg"
                val sub = idSoFar
                dir = dir.dirs.getOrPut(seg) { Dir(sub, seg) }
            }
            val name = segments.last()
            dir.files.add(Node.file("$idSoFar/$name", name, e.size, e.modifiedMillis, e.mimeType, e.checksum, e.link))
        }
        return toNode(root)
    }

    private fun toNode(dir: Dir): Node =
        Node.directory(dir.id, dir.name, dir.dirs.values.map { toNode(it) } + dir.files)
}
