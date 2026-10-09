package com.diagramm.model

enum class FileCategory { FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APP, OTHER }

object FileCategorizer {
    private val images = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "raw", "dng", "cr2", "nef", "arw", "tiff", "tif", "avif")
    private val videos = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v", "mpg", "mpeg", "ts")
    private val audio = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma", "amr", "mid", "midi")
    private val documents = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp", "csv", "epub",
        "fb2", "djvu", "md", "html", "htm", "json", "xml",
    )
    private val archives = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso", "img", "cab")
    private val apps = setOf("apk", "xapk", "apks", "aab", "obb")

    fun of(node: Node): FileCategory = when (node.kind) {
        NodeKind.DIRECTORY -> FileCategory.FOLDER
        NodeKind.FILE -> categorize(node.name, node.mimeType)
        else -> FileCategory.OTHER
    }

    fun categorize(name: String, mimeType: String? = null): FileCategory {
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            ext in apps -> return FileCategory.APP
            ext in images -> return FileCategory.IMAGE
            ext in videos -> return FileCategory.VIDEO
            ext in audio -> return FileCategory.AUDIO
            ext in archives -> return FileCategory.ARCHIVE
            ext in documents -> return FileCategory.DOCUMENT
        }
        val mime = mimeType?.lowercase() ?: return FileCategory.OTHER
        return when {
            mime.startsWith("image/") -> FileCategory.IMAGE
            mime.startsWith("video/") -> FileCategory.VIDEO
            mime.startsWith("audio/") -> FileCategory.AUDIO
            mime == "application/vnd.android.package-archive" -> FileCategory.APP
            mime == "application/zip" || mime == "application/x-rar-compressed" || mime == "application/x-7z-compressed" ||
                mime == "application/gzip" || mime == "application/x-tar" -> FileCategory.ARCHIVE
            mime.startsWith("text/") || mime == "application/pdf" || mime.contains("document") ||
                mime.contains("spreadsheet") || mime.contains("presentation") -> FileCategory.DOCUMENT
            else -> FileCategory.OTHER
        }
    }

    /** Total bytes per category over all files below [root]. */
    fun totals(root: Node): Map<FileCategory, Long> {
        val result = LinkedHashMap<FileCategory, Long>()
        for (n in root.walk()) {
            if (n.isFile) result.merge(of(n), n.size, Long::plus)
        }
        return result
    }
}
