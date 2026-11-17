package com.karelisio.fichio.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Subset of the GitHub "release" JSON we care about. */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String,
    @SerialName("content_type") val contentType: String? = null,
)

/** A release that is newer than the running build. */
data class AvailableUpdate(
    val version: String,
    val changelog: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkName: String,
    val pageUrl: String?,
)

/** Pure helpers: version comparison, release parsing, changelog extraction. */
object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun parse(raw: String): GitHubRelease = json.decodeFromString(GitHubRelease.serializer(), raw)

    /** "v1.2.3" / "1.2.3-debug" → "1.2.3". */
    fun normalize(version: String): String =
        version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+').trim()

    /**
     * Semantic comparison of dotted numeric versions; suffixes (-debug, -rc1, +build) are ignored
     * and missing components count as 0 (1.2 == 1.2.0).
     */
    fun compare(a: String, b: String): Int {
        val pa = normalize(a).split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = normalize(b).split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(candidate: String, current: String) = compare(candidate, current) > 0

    /** Maps a release to an [AvailableUpdate] when it has an APK asset; null otherwise. */
    fun toUpdate(release: GitHubRelease): AvailableUpdate? {
        if (release.draft || release.prerelease) return null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
        return AvailableUpdate(
            version = normalize(release.tagName),
            changelog = release.body.orEmpty().trim(),
            apkUrl = apk.downloadUrl,
            apkSize = apk.size,
            apkName = apk.name,
            pageUrl = release.htmlUrl,
        )
    }

    /**
     * Extracts the body of the "## [X.Y.Z]" section of a Keep-a-Changelog file (same rule as the
     * CI workflow). Returns null when the version has no section.
     */
    fun changelogSection(changelog: String, version: String): String? {
        val v = Regex.escape(normalize(version))
        val re = Regex("""^## \[?v?$v(?![0-9.])[^\n]*\n(.*?)(?=^## |\z)""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
        return re.find(changelog)?.groupValues?.get(1)?.trim()
    }
}

/** Minimal Markdown model used by the update sheet (headings, bullets, paragraphs, **bold**). */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String, val depth: Int) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

object SimpleMarkdown {
    fun parse(md: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }
        md.replace("\r\n", "\n").lines().forEach { raw ->
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            when {
                trimmed.isEmpty() -> flush()
                trimmed.startsWith("#") -> {
                    flush()
                    val level = trimmed.takeWhile { it == '#' }.length
                    out += MdBlock.Heading(level.coerceIn(1, 6), trimmed.drop(level).trim())
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                    flush()
                    val indent = line.length - trimmed.length
                    out += MdBlock.Bullet(trimmed.drop(2).trim(), indent / 2)
                }
                else -> {
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(trimmed)
                }
            }
        }
        flush()
        return out
    }

    /** Splits text into (segment, isBold) runs on `**`. */
    fun boldRuns(text: String): List<Pair<String, Boolean>> {
        val parts = text.split("**")
        val balanced = parts.size % 2 == 1
        return parts.mapIndexedNotNull { i, s ->
            // An unmatched trailing "**" is kept as plain text.
            val bold = i % 2 == 1 && (balanced || i < parts.size - 1)
            when {
                s.isEmpty() -> null
                !bold && i % 2 == 1 -> "**$s" to false
                else -> s to bold
            }
        }
    }
}
