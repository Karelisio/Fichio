package com.karelisio.fichio.analysis

import androidx.compose.runtime.Immutable
import java.util.IdentityHashMap

/** One ring segment, in frame-normalised coordinates: x in [0, 1] around, depth 1..n outwards. */
@Immutable
class SunburstArc(
    val node: DirNode,
    val depth: Int,
    val x0: Double,
    val x1: Double,
    /** Index of the colour family (top-level folder under the absolute root). */
    val family: Int,
    /** Index among its siblings (shade variation). */
    val sibling: Int,
)

/**
 * Sunburst layout over a [DirNode] tree. Display children are memoised by identity so that
 * "Autres" aggregates keep the same identity across layouts (needed by zoom transitions).
 * Pure Kotlin, unit-testable.
 */
class SunburstModel(
    val root: DirNode,
    private val minFraction: Double = 0.018,
    private val maxItems: Int = 14,
) {
    private val cache = IdentityHashMap<DirNode, List<DirNode>>()
    /** Colour family of every node seen in a layout (identity). */
    private val families = IdentityHashMap<DirNode, Int>()

    /** Children shown for [node]: the biggest ones, with small ones aggregated into "Autres". */
    fun displayChildren(node: DirNode): List<DirNode> = synchronized(cache) {
        cache.getOrPut(node) { aggregate(node) }
    }

    private fun aggregate(node: DirNode): List<DirNode> {
        val kids = node.children.filter { it.size > 0 }
        if (kids.isEmpty() || node.size <= 0) return emptyList()
        val keep = ArrayList<DirNode>()
        val rest = ArrayList<DirNode>()
        for (c in kids) {
            val frac = c.size.toDouble() / node.size
            if (keep.size < maxItems && frac >= minFraction) keep += c else rest += c
        }
        if (rest.size == 1) {
            keep += rest
        } else if (rest.isNotEmpty()) {
            keep += DirNode(
                path = "${node.dirPath}/\u0001autres",
                name = "Autres",
                size = rest.sumOf { it.size },
                fileCount = rest.sumOf { it.fileCount },
                children = rest,
                kind = NodeKind.OTHERS,
                dirPath = node.dirPath,
            )
        }
        return keep
    }

    fun familyOf(node: DirNode): Int = synchronized(cache) { families[node] } ?: 0

    /**
     * Arcs of [frame] up to [maxDepth] rings. When [deep] (a descendant at depth d) is given,
     * its own subtree is additionally laid out down to depth d + [maxDepth], so a zoom
     * transition towards it already has the rings it will show.
     */
    fun layout(frame: DirNode, maxDepth: Int = 3, deep: DirNode? = null): List<SunburstArc> {
        val out = ArrayList<SunburstArc>()
        val frameFamily = if (frame === root) null else familyOf(frame)
        fun visit(node: DirNode, depth: Int, x0: Double, x1: Double, family: Int?, inDeep: Boolean, deepDepth: Int) {
            val kids = displayChildren(node)
            if (kids.isEmpty() || node.size <= 0) return
            var x = x0
            val span = x1 - x0
            kids.forEachIndexed { i, c ->
                val w = span * c.size / node.size
                val fam = family ?: i
                synchronized(cache) { families[c] = fam }
                val childDepth = depth + 1
                val isDeep = inDeep || c === deep
                val dd = if (c === deep) childDepth else deepDepth
                val limit = if (isDeep) dd + maxDepth else maxDepth
                if (childDepth <= limit && w > 1e-4) {
                    out += SunburstArc(c, childDepth, x, x + w, fam, i)
                    visit(c, childDepth, x, x + w, fam, isDeep, dd)
                }
                x += w
            }
        }
        visit(frame, 0, 0.0, 1.0, frameFamily, false, 0)
        return out
    }
}

/** Visible window over a frame layout: x range and how many rings are "consumed" by the zoom. */
@Immutable
data class SunburstViewport(val x0: Double = 0.0, val x1: Double = 1.0, val y: Double = 0.0) {
    fun lerp(to: SunburstViewport, t: Double) = SunburstViewport(
        x0 + (to.x0 - x0) * t,
        x1 + (to.x1 - x1) * t,
        y + (to.y - y) * t,
    )

    /** Fraction [0, 1] around the circle of a frame x coordinate. */
    fun angleFraction(x: Double): Double = ((x - x0) / (x1 - x0)).coerceIn(0.0, 1.0)
}
