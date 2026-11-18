package com.karelisio.fichio.ops

import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import androidx.compose.runtime.Immutable
import com.karelisio.fichio.ops.engine.Names
import java.util.Locale

enum class CaseChange { NONE, LOWER, UPPER, TITLE }

enum class NumberPosition { START, END, REPLACE }

/** Every rule of the batch-rename sheet. Applied in a fixed order, see [BatchRename.newName]. */
@Immutable
data class BatchRenameRules(
    val prefix: String = "",
    val suffix: String = "",
    val numbering: Boolean = false,
    val numberStart: Int = 1,
    val numberStep: Int = 1,
    val numberDigits: Int = 1,
    val numberPosition: NumberPosition = NumberPosition.END,
    /** Placed between the number and the name (not used in [NumberPosition.REPLACE]). */
    val numberSeparator: String = " ",
    val find: String = "",
    val replace: String = "",
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val case: CaseChange = CaseChange.NONE,
    /** When true, rules only touch the base name and the extension stays as is. */
    val keepExtension: Boolean = true,
)

enum class RenameStatus { OK, UNCHANGED, COLLISION, INVALID }

@Immutable
data class RenamePreviewItem(
    val path: String,
    val oldName: String,
    val newName: String,
    val status: RenameStatus,
    val message: String? = null,
)

@Immutable
data class BatchRenamePreview(
    val items: List<RenamePreviewItem>,
    /** Set when the search pattern is not a valid regular expression. */
    val patternError: String? = null,
) {
    val changed get() = items.count { it.status == RenameStatus.OK }
    val problems get() = items.count { it.status == RenameStatus.COLLISION || it.status == RenameStatus.INVALID }
    val canApply get() = patternError == null && problems == 0 && changed > 0

    /** (path, newName) pairs for [OperationManager.batchRename]. */
    fun renames(): List<Pair<String, String>> = items.filter { it.status == RenameStatus.OK }.map { it.path to it.newName }
}

/** Input row: what the preview needs to know about an entry. */
data class RenameSource(val path: String, val name: String, val isDirectory: Boolean)

/** Pure batch-rename logic (live preview + collision detection). */
object BatchRename {

    /**
     * New name of the [index]-th item. Order: find/replace → case → numbering → prefix/suffix,
     * all on the base name when [BatchRenameRules.keepExtension] (folders never have one).
     */
    fun newName(rules: BatchRenameRules, name: String, isDirectory: Boolean, index: Int, pattern: Regex? = compile(rules).getOrNull()): String {
        val (base0, ext) = if (rules.keepExtension) Names.split(name, isDirectory) else name to ""
        var base = base0
        if (rules.find.isNotEmpty()) {
            base = if (rules.regex) {
                pattern?.let { runCatching { base.replace(it, rules.replace) }.getOrDefault(base) } ?: base
            } else {
                base.replace(rules.find, rules.replace, ignoreCase = !rules.caseSensitive)
            }
        }
        base = when (rules.case) {
            CaseChange.NONE -> base
            CaseChange.LOWER -> base.lowercase(Locale.FRENCH)
            CaseChange.UPPER -> base.uppercase(Locale.FRENCH)
            CaseChange.TITLE -> titleCase(base)
        }
        if (rules.numbering) {
            val n = rules.numberStart.toLong() + index.toLong() * rules.numberStep
            val digits = rules.numberDigits.coerceIn(1, 9)
            val num = if (n < 0) "-" + (-n).toString().padStart(digits, '0') else n.toString().padStart(digits, '0')
            base = when (rules.numberPosition) {
                NumberPosition.START -> num + rules.numberSeparator + base
                NumberPosition.END -> base + rules.numberSeparator + num
                NumberPosition.REPLACE -> num
            }
        }
        return rules.prefix + base + rules.suffix + ext
    }

    fun compile(rules: BatchRenameRules): Result<Regex?> {
        if (!rules.regex || rules.find.isEmpty()) return Result.success(null)
        return runCatching {
            if (rules.caseSensitive) Regex(rules.find) else Regex(rules.find, RegexOption.IGNORE_CASE)
        }
    }

    /**
     * Computes the preview for [sources] (in display order). [siblingNames] lists, per parent
     * folder, the names already present there; names of items being renamed are freed.
     * Comparisons ignore case because shared storage is case-insensitive.
     */
    fun preview(
        sources: List<RenameSource>,
        rules: BatchRenameRules,
        siblingNames: (parent: String) -> Collection<String> = { emptyList() },
    ): BatchRenamePreview {
        val compiled = compile(rules)
        val patternError = compiled.exceptionOrNull()?.let { Strings.get(R.string.ops_rename_bad_regex) }
        val pattern = compiled.getOrNull()
        val computed = sources.mapIndexed { i, s ->
            s to if (patternError != null) s.name else newName(rules, s.name, s.isDirectory, i, pattern)
        }

        // Names that will be occupied per folder: untouched siblings + every final name.
        val byParent = computed.groupBy { parentOf(it.first.path) }
        val occupied = HashMap<String, Map<String, Int>>()
        for ((parent, rows) in byParent) {
            val renamedAway = rows.map { it.first.name.lowercase() }.toSet()
            val counts = HashMap<String, Int>()
            siblingNames(parent).map { it.lowercase() }.filter { it !in renamedAway }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
            rows.forEach { (_, new) -> val k = new.lowercase(); counts[k] = (counts[k] ?: 0) + 1 }
            occupied[parent] = counts
        }

        val items = computed.map { (s, new) ->
            val counts = occupied.getValue(parentOf(s.path))
            val invalid = Names.validate(new)
            when {
                new == s.name -> RenamePreviewItem(s.path, s.name, new, RenameStatus.UNCHANGED)
                invalid != null -> RenamePreviewItem(s.path, s.name, new, RenameStatus.INVALID, invalid)
                (counts[new.lowercase()] ?: 0) > 1 -> RenamePreviewItem(s.path, s.name, new, RenameStatus.COLLISION, Strings.get(R.string.ops_rename_name_taken))
                else -> RenamePreviewItem(s.path, s.name, new, RenameStatus.OK)
            }
        }
        return BatchRenamePreview(items, patternError)
    }

    private fun parentOf(path: String) = path.substringBeforeLast('/', "/").ifEmpty { "/" }

    /** "le petit CHAT-noir" → "Le Petit Chat-Noir". */
    fun titleCase(s: String): String {
        val sb = StringBuilder(s.length)
        var startOfWord = true
        for (c in s) {
            if (c.isLetterOrDigit()) {
                sb.append(if (startOfWord) c.titlecaseChar() else c.lowercaseChar())
                startOfWord = false
            } else {
                sb.append(c)
                startOfWord = c != '\''
            }
        }
        return sb.toString()
    }
}
