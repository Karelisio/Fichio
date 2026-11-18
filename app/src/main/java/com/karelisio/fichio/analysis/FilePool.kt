package com.karelisio.fichio.analysis

import androidx.compose.runtime.Immutable
import java.util.BitSet

/**
 * Size distribution of every scanned file (log2 buckets: bucket k holds sizes in [2^(k-1), 2^k),
 * bucket 0 the empty files). Cheap (2 × 64 longs) and complete, even for files the pool dropped.
 */
@Immutable
class SizeHistogram internal constructor(
    private val counts: LongArray = LongArray(BUCKETS),
    private val bytes: LongArray = LongArray(BUCKETS),
) {
    internal fun add(size: Long) {
        val b = bucketOf(size)
        counts[b]++
        bytes[b] += size
    }

    internal fun addAll(other: SizeHistogram) {
        for (i in 0 until BUCKETS) {
            counts[i] += other.counts[i]
            bytes[i] += other.bytes[i]
        }
    }

    /** Number of files of at least [minSize] bytes (exact on bucket bounds, else rounded down to the bucket). */
    fun countAtLeast(minSize: Long): Long = (bucketOf(minSize) until BUCKETS).sumOf { counts[it] }

    /** (lower bound in bytes, file count, total bytes) for each non-empty bucket, smallest first. */
    fun buckets(): List<Triple<Long, Long, Long>> =
        (0 until BUCKETS).filter { counts[it] > 0 }.map { Triple(lowerBound(it), counts[it], bytes[it]) }

    companion object {
        const val BUCKETS = 64
        fun bucketOf(size: Long): Int = if (size <= 0) 0 else 64 - java.lang.Long.numberOfLeadingZeros(size)
        fun lowerBound(bucket: Int): Long = if (bucket == 0) 0 else 1L shl (bucket - 1)
    }
}

/**
 * Growable, object-free store of the files met by one walker: per file a directory id, a UTF-8
 * name slice, a size and a date (~50 bytes instead of a FileRef + full path String). When it
 * holds [cap] files it drops its smaller half and raises [floor], so memory stays bounded on
 * huge storages; every file of at least [floor] bytes is kept.
 */
internal class PoolBuilder(private val minSize: Long, private val cap: Int) {
    var floor = minSize
        private set
    val dirs = ArrayList<String>()
    var count = 0
        private set
    var dirIdx = IntArray(256)
        private set
    var nameEnd = IntArray(256)
        private set
    var names = ByteArray(8192)
        private set
    var nameLen = 0
        private set
    var sizes = LongArray(256)
        private set
    var mtimes = LongArray(256)
        private set

    fun addDir(path: String): Int {
        dirs += path
        return dirs.size - 1
    }

    fun add(dir: Int, name: String, size: Long, mtime: Long) {
        if (size < floor) return
        if (count == sizes.size) grow()
        val bytes = name.toByteArray(Charsets.UTF_8)
        if (nameLen + bytes.size > names.size) names = names.copyOf(maxOf(names.size * 2, nameLen + bytes.size))
        System.arraycopy(bytes, 0, names, nameLen, bytes.size)
        nameLen += bytes.size
        dirIdx[count] = dir
        nameEnd[count] = nameLen
        sizes[count] = size
        mtimes[count] = mtime
        count++
        if (count >= cap) compact()
    }

    private fun grow() {
        val n = sizes.size * 2
        dirIdx = dirIdx.copyOf(n); nameEnd = nameEnd.copyOf(n)
        sizes = sizes.copyOf(n); mtimes = mtimes.copyOf(n)
    }

    /** Keeps roughly the biggest half (always makes room). */
    private fun compact() {
        val sorted = sizes.copyOf(count).also { it.sort() }
        var newFloor = sorted[count / 2]
        if (sorted.count { it >= newFloor } >= cap) newFloor++
        floor = maxOf(floor, newFloor)
        retain { sizes[it] >= floor }
    }

    private inline fun retain(keep: (Int) -> Boolean) {
        var w = 0
        var nameW = 0
        var start = 0
        for (r in 0 until count) {
            val end = nameEnd[r]
            if (keep(r)) {
                val len = end - start
                System.arraycopy(names, start, names, nameW, len)
                nameW += len
                dirIdx[w] = dirIdx[r]; nameEnd[w] = nameW; sizes[w] = sizes[r]; mtimes[w] = mtimes[r]
                w++
            }
            start = end
        }
        count = w
        nameLen = nameW
    }

    fun nameStart(i: Int) = if (i == 0) 0 else nameEnd[i - 1]
}

/**
 * Every scanned file of at least [floor] bytes, kept compactly so that the analysis lists (big,
 * old, duplicate candidates) are recomputed instantly for any threshold, without rescanning.
 * Immutable: [without] returns a copy with deleted items masked out.
 */
@Immutable
class FilePool private constructor(
    private val dirs: Array<String>,
    private val dirIdx: IntArray,
    private val nameEnd: IntArray,
    private val names: ByteArray,
    private val sizes: LongArray,
    private val mtimes: LongArray,
    /** Indices by size, biggest first. */
    private val order: IntArray,
    /** All files of at least this size are present (raised when the pool hit its cap). */
    val floor: Long,
    private val gone: BitSet,
) {
    val size: Int get() = sizes.size - gone.cardinality()

    private fun nameStart(i: Int) = if (i == 0) 0 else nameEnd[i - 1]

    fun path(i: Int): String {
        val start = nameStart(i)
        return dirs[dirIdx[i]] + "/" + String(names, start, nameEnd[i] - start, Charsets.UTF_8)
    }

    private fun ref(i: Int) = FileRef(path(i), sizes[i], mtimes[i])

    /**
     * Files of at least [minSize] bytes matching [match] (size, date) and [accept] (path),
     * biggest first, at most [limit].
     */
    fun select(
        minSize: Long,
        limit: Int = Int.MAX_VALUE,
        match: (size: Long, mtime: Long) -> Boolean = { _, _ -> true },
        accept: (String) -> Boolean = { true },
    ): List<FileRef> {
        val out = ArrayList<FileRef>()
        for (i in order) {
            val s = sizes[i]
            if (s < minSize) break
            if (gone[i] || !match(s, mtimes[i])) continue
            val p = path(i)
            if (!accept(p)) continue
            out += FileRef(p, s, mtimes[i])
            if (out.size >= limit) break
        }
        return out
    }

    /** (count, bytes) of the files [select] would return without limit nor path filter; no path decoded. */
    fun stats(minSize: Long, match: (size: Long, mtime: Long) -> Boolean = { _, _ -> true }): Pair<Int, Long> {
        var n = 0
        var bytes = 0L
        for (i in order) {
            val s = sizes[i]
            if (s < minSize) break
            if (gone[i] || !match(s, mtimes[i])) continue
            n++; bytes += s
        }
        return n to bytes
    }

    /** Files sharing their exact size with another one, for sizes in [minSize, maxExclusive). */
    fun sameSizeCandidates(minSize: Long, maxExclusive: Long = Long.MAX_VALUE): List<FileRef> {
        val out = ArrayList<FileRef>()
        var k = 0
        while (k < order.size) {
            val s = sizes[order[k]]
            var end = k + 1
            while (end < order.size && sizes[order[end]] == s) end++
            if (s < minSize) break
            if (s < maxExclusive) {
                val live = (k until end).map { order[it] }.filterNot { gone[it] }
                if (live.size >= 2) live.forEach { out += ref(it) }
            }
            k = end
        }
        return out
    }

    /** Sizes of the given paths that are in the pool. */
    fun sizesOf(paths: Set<String>): Map<String, Long> {
        val out = HashMap<String, Long>()
        forEachMatching(paths, emptySet()) { i, p -> out[p] = sizes[i] }
        return out
    }

    /** Copy with the given files and everything under the given directories masked out. */
    fun without(files: Set<String>, dirsRemoved: Set<String>): FilePool {
        if (files.isEmpty() && dirsRemoved.isEmpty()) return this
        val mask = gone.clone() as BitSet
        forEachMatching(files, dirsRemoved) { i, _ -> mask.set(i) }
        return FilePool(dirs, dirIdx, nameEnd, names, sizes, mtimes, order, floor, mask)
    }

    /** A directory is gone when it or one of its ancestors was removed (walks up: O(depth)). */
    private fun inRemovedTree(dir: String, removed: Set<String>): Boolean {
        var p = dir
        while (true) {
            if (p in removed) return true
            val i = p.lastIndexOf('/')
            if (i <= 0) return false
            p = p.substring(0, i)
        }
    }

    private inline fun forEachMatching(files: Set<String>, dirsRemoved: Set<String>, block: (Int, String) -> Unit) {
        // Only files whose directory is concerned are decoded.
        val parents = files.mapTo(HashSet()) { it.substringBeforeLast('/') }
        val wholeDir = BooleanArray(dirs.size)
        val someFiles = BooleanArray(dirs.size)
        dirs.forEachIndexed { d, dir ->
            if (dirsRemoved.isNotEmpty() && inRemovedTree(dir, dirsRemoved)) wholeDir[d] = true
            if (dir in parents) someFiles[d] = true
        }
        for (i in sizes.indices) {
            if (gone[i]) continue
            val d = dirIdx[i]
            if (wholeDir[d]) block(i, path(i))
            else if (someFiles[d]) {
                val p = path(i)
                if (p in files) block(i, p)
            }
        }
    }

    companion object {
        /** Max files kept in total: indices are packed with sizes in one long to sort them. */
        const val MAX_FILES = 1 shl 20
        private const val SIZE_BITS = 43

        val EMPTY = FilePool(emptyArray(), IntArray(0), IntArray(0), ByteArray(0), LongArray(0), LongArray(0), IntArray(0), 0, BitSet())

        internal fun merge(parts: List<PoolBuilder>, cap: Int): FilePool {
            val total = parts.sumOf { it.count }
            var floor = parts.maxOfOrNull { it.floor } ?: 0L
            val limit = minOf(cap, MAX_FILES)
            if (total > limit) {
                val all = LongArray(total)
                var k = 0
                parts.forEach { b -> for (i in 0 until b.count) all[k++] = b.sizes[i] }
                all.sort()
                var cut = all[total - limit]
                if (all.count { it >= cut } > limit) cut++
                floor = maxOf(floor, cut)
            }
            val n = parts.sumOf { b -> (0 until b.count).count { b.sizes[it] >= floor } }
            val dirs = ArrayList<String>()
            val dirIdx = IntArray(n)
            val nameEnd = IntArray(n)
            val sizes = LongArray(n)
            val mtimes = LongArray(n)
            val nameBytes = parts.sumOf { it.nameLen }
            val names = ByteArray(nameBytes)
            var w = 0
            var nameW = 0
            for (b in parts) {
                val dirBase = dirs.size
                dirs += b.dirs
                for (i in 0 until b.count) {
                    if (b.sizes[i] < floor) continue
                    val start = b.nameStart(i)
                    val len = b.nameEnd[i] - start
                    System.arraycopy(b.names, start, names, nameW, len)
                    nameW += len
                    dirIdx[w] = dirBase + b.dirIdx[i]
                    nameEnd[w] = nameW
                    sizes[w] = b.sizes[i]
                    mtimes[w] = b.mtimes[i]
                    w++
                }
            }
            val maxSize = (1L shl SIZE_BITS) - 1
            val keys = LongArray(n) { (sizes[it].coerceIn(0, maxSize) shl 20) or it.toLong() }
            keys.sort()
            val order = IntArray(n) { (keys[n - 1 - it] and (MAX_FILES - 1).toLong()).toInt() }
            return FilePool(
                dirs.toTypedArray(), dirIdx, nameEnd, names.copyOf(nameW), sizes, mtimes, order,
                floor, BitSet(n),
            )
        }
    }
}
