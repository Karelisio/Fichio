package com.karelisio.fichio.core.fs

/**
 * Mapping between absolute paths on a removable volume and the document ids of
 * ExternalStorageProvider (`com.android.externalstorage.documents`): a document id is
 * `<volumeId>:<path relative to the volume root>`, e.g. `1234-ABCD:DCIM/Camera/a.jpg`, and a granted
 * tree is identified by the document id of its top folder (`1234-ABCD:` for the volume root,
 * `1234-ABCD:Music` for a sub-folder). Pure JVM, unit-tested.
 */
object SafPaths {
    const val AUTHORITY = "com.android.externalstorage.documents"

    /** Volume id used by the provider when the platform gives none: the last path segment (`/storage/1234-ABCD`). */
    fun fallbackVolumeId(volumeRoot: String): String = volumeRoot.trimEnd('/').substringAfterLast('/')

    fun rootDocumentId(volumeId: String) = "$volumeId:"

    private fun relativeTo(volumeRoot: String, path: String): String? {
        val root = volumeRoot.trimEnd('/')
        val p = path.trimEnd('/')
        return when {
            p == root -> ""
            p.startsWith("$root/") -> p.substring(root.length + 1)
            else -> null
        }
    }

    private fun treeVolume(treeDocId: String) = treeDocId.substringBefore(':')
    private fun treeRelative(treeDocId: String) = treeDocId.substringAfter(':', "").trim('/')

    /** True when [treeDocId] is a tree of volume [volumeId] (ids compare case-insensitively, as FAT UUIDs do). */
    fun isTreeOf(volumeId: String, treeDocId: String): Boolean =
        treeDocId.contains(':') && treeVolume(treeDocId).equals(volumeId, ignoreCase = true)

    /** True when the tree is the whole volume (what Fichio asks for). */
    fun isVolumeRoot(treeDocId: String): Boolean = treeDocId.contains(':') && treeRelative(treeDocId).isEmpty()

    /** True when [path] (on the volume mounted at [volumeRoot]) lies inside the granted tree. */
    fun covers(volumeRoot: String, volumeId: String, treeDocId: String, path: String): Boolean =
        documentIdFor(volumeRoot, volumeId, treeDocId, path) != null

    /** Document id of [path], or null when it is outside the volume or the granted tree. */
    fun documentIdFor(volumeRoot: String, volumeId: String, treeDocId: String, path: String): String? {
        if (!isTreeOf(volumeId, treeDocId)) return null
        val rel = relativeTo(volumeRoot, path) ?: return null
        val top = treeRelative(treeDocId)
        if (top.isNotEmpty() && rel != top && !rel.startsWith("$top/")) return null
        return "${treeVolume(treeDocId)}:$rel"
    }

    /** Absolute path of [documentId], or null when it belongs to another volume or is outside the tree. */
    fun pathFor(volumeRoot: String, volumeId: String, treeDocId: String, documentId: String): String? {
        if (!isTreeOf(volumeId, treeDocId) || !documentId.contains(':')) return null
        if (!documentId.substringBefore(':').equals(volumeId, ignoreCase = true)) return null
        val rel = documentId.substringAfter(':').trim('/')
        val top = treeRelative(treeDocId)
        if (top.isNotEmpty() && rel != top && !rel.startsWith("$top/")) return null
        val root = volumeRoot.trimEnd('/')
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    /**
     * The folders to create, top-down, so that [path] exists: the deepest existing ancestor is
     * found by [exists]; returns the missing paths (empty when [path] already exists), or null
     * when even the tree's top folder is missing.
     */
    fun missingDirs(volumeRoot: String, volumeId: String, treeDocId: String, path: String, exists: (String) -> Boolean): List<String>? {
        val top = pathFor(volumeRoot, volumeId, treeDocId, "${treeVolume(treeDocId)}:${treeRelative(treeDocId)}") ?: return null
        if (documentIdFor(volumeRoot, volumeId, treeDocId, path) == null) return null
        val missing = ArrayList<String>()
        var p = path.trimEnd('/')
        while (p != top && !exists(p)) {
            missing += p
            p = p.substringBeforeLast('/')
        }
        if (p == top && !exists(top)) return null
        return missing.asReversed()
    }
}
