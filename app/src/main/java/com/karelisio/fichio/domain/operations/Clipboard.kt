package com.karelisio.fichio.domain.operations

/**
 * In-memory copy/cut clipboard. Deliberately not persisted: the clipboard is
 * cleared when the process dies, matching user expectations for a file manager.
 */
object Clipboard {

    var sources: List<String> = emptyList()
        private set
    var isCut: Boolean = false
        private set

    val hasItems: Boolean get() = sources.isNotEmpty()

    fun copy(paths: List<String>) {
        sources = paths.toList()
        isCut = false
    }

    fun cut(paths: List<String>) {
        sources = paths.toList()
        isCut = true
    }

    fun clear() {
        sources = emptyList()
        isCut = false
    }
}

/**
 * Holds a single reversible action ("undo the last operation"). Operations
 * register a suspend revert closure; [undo] runs the most recent one once.
 */
object UndoStack {

    private var last: (suspend () -> Unit)? = null
    private var label: String = ""

    fun offer(label: String = "", revert: suspend () -> Unit) {
        this.label = label
        last = revert
    }

    suspend fun undo(): Boolean {
        val op = last ?: return false
        last = null
        op.invoke()
        return true
    }

    fun clear() {
        last = null
        label = ""
    }
}
