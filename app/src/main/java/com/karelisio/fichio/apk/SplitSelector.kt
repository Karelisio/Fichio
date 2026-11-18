package com.karelisio.fichio.apk

import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import androidx.compose.runtime.Immutable
import java.security.MessageDigest
import java.util.Locale

/* Pure logic of the APK module (no Android dependency): split classification, selection, formatting. */

enum class SplitKind { BASE, FEATURE, ABI, DENSITY, LANGUAGE, OTHER_CONFIG }

@Immutable
data class SplitInfo(
    /** Entry name inside the bundle (may include folders, e.g. "splits/base-master.apk"). */
    val entryName: String,
    val kind: SplitKind,
    /** ABI ("arm64_v8a"), density ("xxhdpi"), language ("fr") or other qualifier. */
    val qualifier: String? = null,
    val size: Long = -1,
) {
    val fileName: String get() = entryName.substringAfterLast('/')

    val displayName: String
        get() = when (kind) {
            SplitKind.BASE -> Strings.get(R.string.apk_split_base)
            SplitKind.FEATURE -> Strings.get(R.string.apk_split_module, fileName.removeSuffix(".apk").removePrefix("split_"))
            SplitKind.ABI -> Strings.get(R.string.apk_split_abi, SplitSelector.abiToAndroid(qualifier.orEmpty()))
            SplitKind.DENSITY -> Strings.get(R.string.apk_split_density, qualifier.orEmpty())
            SplitKind.LANGUAGE -> Strings.get(R.string.apk_split_language, languageName(qualifier.orEmpty()))
            SplitKind.OTHER_CONFIG -> Strings.get(R.string.apk_split_config, qualifier.orEmpty())
        }

    private fun languageName(q: String): String {
        val lang = q.substringBefore('_')
        val name = Locale.forLanguageTag(lang).getDisplayLanguage(Strings.locale)
        return if (name.isNullOrBlank() || name == lang) q else "$name ($q)"
    }
}

object SplitSelector {
    /** Density buckets in dpi. */
    val densities: Map<String, Int> = mapOf(
        "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
        "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
    )
    private val noDensity = setOf("nodpi", "anydpi")
    val abis: Set<String> = setOf("armeabi", "armeabi_v7a", "arm64_v8a", "x86", "x86_64", "mips", "mips64", "riscv64")
    private val languageRegex = Regex("^[a-z]{2,3}([_-](r?[a-z]{2}|[0-9]{3}|[a-z]{4}))?$")

    /** "arm64_v8a" ⇄ "arm64-v8a" (split qualifiers use underscores, Build.SUPPORTED_ABIS dashes). */
    fun abiToSplit(abi: String) = abi.lowercase().replace('-', '_')
    fun abiToAndroid(abi: String) = abi.lowercase().replace('_', '-')

    private fun classifyQualifier(q: String): Pair<SplitKind, String> {
        val n = q.lowercase()
        val underscored = n.replace('-', '_')
        return when {
            underscored in abis -> SplitKind.ABI to underscored
            n in densities || n in noDensity -> SplitKind.DENSITY to n
            languageRegex.matches(n) -> SplitKind.LANGUAGE to underscored
            else -> SplitKind.OTHER_CONFIG to n
        }
    }

    /**
     * Classifies one APK of a bundle. Understands the common layouts:
     * SAI / APKMirror ("base.apk", "split_config.arm64_v8a.apk"), XAPK ("config.fr.apk") and
     * bundletool ("splits/base-master.apk", "splits/base-xxhdpi.apk").
     */
    fun classify(entryName: String, size: Long = -1): SplitInfo {
        val name = entryName.substringAfterLast('/').removeSuffix(".apk").removeSuffix(".APK")
        val stripped = name.removePrefix("split_")
        if (name == "base" || name == "base-master") return SplitInfo(entryName, SplitKind.BASE, null, size)
        if (stripped.contains("config.")) {
            val (kind, q) = classifyQualifier(stripped.substringAfterLast("config."))
            return SplitInfo(entryName, kind, q, size)
        }
        if (stripped.contains('-')) {
            val module = stripped.substringBefore('-')
            val q = stripped.substringAfter('-')
            if (q == "master") return SplitInfo(entryName, if (module == "base") SplitKind.BASE else SplitKind.FEATURE, null, size)
            val (kind, qual) = classifyQualifier(q)
            if (kind != SplitKind.OTHER_CONFIG) return SplitInfo(entryName, kind, qual, size)
        }
        return SplitInfo(entryName, SplitKind.FEATURE, null, size)
    }

    /**
     * Classifies every APK of a bundle and makes sure exactly one is the base: "base.apk" when
     * present, otherwise (XAPK "<package>.apk") the largest non-config APK.
     */
    fun classifyAll(entries: List<Pair<String, Long>>): List<SplitInfo> {
        val list = entries.map { (name, size) -> classify(name, size) }
        val bases = list.filter { it.kind == SplitKind.BASE }
        val base = when {
            bases.size == 1 -> bases.first()
            bases.size > 1 -> bases.firstOrNull { it.fileName == "base.apk" } ?: bases.maxBy { it.size }
            else -> list.filter { it.kind == SplitKind.FEATURE }.maxByOrNull { it.size }
        }
        return list.map {
            when {
                it === base -> it.copy(kind = SplitKind.BASE)
                it.kind == SplitKind.BASE -> it.copy(kind = SplitKind.FEATURE)
                else -> it
            }
        }
    }

    /**
     * Splits to install on a device: base, feature modules and unknown configs always; the best
     * ABI (device preference order); the closest density at or above the screen's (else the
     * highest); the languages of the device.
     */
    fun select(
        splits: List<SplitInfo>,
        supportedAbis: List<String>,
        densityDpi: Int,
        languages: List<String>,
    ): List<SplitInfo> {
        val abiSplits = splits.filter { it.kind == SplitKind.ABI }
        val bestAbi = supportedAbis.map(::abiToSplit).firstOrNull { abi -> abiSplits.any { it.qualifier == abi } }

        val densitySplits = splits.filter { it.kind == SplitKind.DENSITY }
        val available = densitySplits.mapNotNull { s -> densities[s.qualifier]?.let { s.qualifier!! to it } }.distinct()
        val bestDensity = available.filter { it.second >= densityDpi }.minByOrNull { it.second }?.first
            ?: available.maxByOrNull { it.second }?.first

        val langs = languages.map { it.lowercase().substringBefore('-').substringBefore('_') }.toSet()

        return splits.filter { s ->
            when (s.kind) {
                SplitKind.BASE, SplitKind.FEATURE, SplitKind.OTHER_CONFIG -> true
                SplitKind.ABI -> s.qualifier == bestAbi
                SplitKind.DENSITY -> s.qualifier == bestDensity || s.qualifier in noDensity
                SplitKind.LANGUAGE -> s.qualifier.orEmpty().substringBefore('_') in langs
            }
        }
    }

    /** ABIs of the device the bundle has native code for (true when it has none). */
    fun abiCompatible(nativeAbis: Collection<String>, supportedAbis: List<String>): Boolean {
        if (nativeAbis.isEmpty()) return true
        val device = supportedAbis.map(::abiToSplit).toSet()
        return nativeAbis.any { abiToSplit(it) in device }
    }

    /** Native ABIs found in APK entry names ("lib/arm64-v8a/libfoo.so"). */
    fun abisFromEntries(names: Sequence<String>): List<String> =
        names.filter { it.startsWith("lib/") && it.endsWith(".so") }
            .mapNotNull { it.split('/').getOrNull(1)?.takeIf { abi -> abi.isNotEmpty() } }
            .distinct().sorted().toList()
}

object ApkFormat {
    /** "AB:CD:EF…" upper-case hex, the format shown by apksigner and the Play Console. */
    fun fingerprint(bytes: ByteArray): String = bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Splits a long fingerprint in two lines for display. */
    fun fingerprintLines(fp: String, perLine: Int = 16): List<String> =
        fp.split(':').chunked(perLine).map { it.joinToString(":") }

    private val illegal = Regex("[\\u0000-\\u001f\\\\/:*?\"<>|]")

    /** "Nom_version.apk" / "Nom_version.apks", safe on any file system. */
    fun exportFileName(label: String, versionName: String?, bundle: Boolean): String {
        val name = label.replace(illegal, "_").trim().trim('.').ifEmpty { "appli" }
        val version = versionName?.replace(illegal, "_")?.trim()?.takeIf { it.isNotEmpty() }
        val base = if (version != null) "${name}_$version" else name
        return base.take(120) + if (bundle) ".apks" else ".apk"
    }

    /** Android version name of an API level, e.g. 26 → "8.0". */
    fun androidVersion(api: Int): String = when (api) {
        in 0..20 -> Strings.get(R.string.apk_android_old)
        21 -> "5.0"; 22 -> "5.1"; 23 -> "6"; 24 -> "7.0"; 25 -> "7.1"; 26 -> "8.0"; 27 -> "8.1"
        28 -> "9"; 29 -> "10"; 30 -> "11"; 31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"
        36 -> "16"; 37 -> "17"
        else -> "API $api"
    }

    /** Human message for a PackageInstaller failure (status + system message). */
    fun installFailureMessage(status: Int, systemMessage: String?): String {
        val sys = systemMessage.orEmpty()
        return when {
            sys.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || sys.contains("signatures do not match", true) ->
                Strings.get(R.string.apk_fail_signature)
            sys.contains("INSTALL_FAILED_VERSION_DOWNGRADE") -> Strings.get(R.string.apk_fail_downgrade)
            sys.contains("INSTALL_FAILED_OLDER_SDK") -> Strings.get(R.string.apk_fail_older_sdk)
            sys.contains("INSTALL_FAILED_NO_MATCHING_ABIS") -> Strings.get(R.string.apk_fail_abi)
            sys.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") -> Strings.get(R.string.apk_fail_storage)
            sys.contains("INSTALL_FAILED_MISSING_SPLIT") -> Strings.get(R.string.apk_fail_missing_split)
            sys.contains("INSTALL_PARSE_FAILED_NO_CERTIFICATES") -> Strings.get(R.string.apk_fail_no_cert)
            sys.contains("INSTALL_FAILED_DEPRECATED_SDK_VERSION") -> Strings.get(R.string.apk_fail_deprecated_sdk)
            else -> when (status) {
                STATUS_FAILURE_ABORTED -> Strings.get(R.string.apk_install_cancelled)
                STATUS_FAILURE_BLOCKED -> Strings.get(R.string.apk_fail_blocked)
                STATUS_FAILURE_CONFLICT -> Strings.get(R.string.apk_fail_conflict)
                STATUS_FAILURE_INCOMPATIBLE -> Strings.get(R.string.apk_fail_incompatible)
                STATUS_FAILURE_INVALID -> Strings.get(R.string.apk_fail_invalid)
                STATUS_FAILURE_STORAGE -> Strings.get(R.string.apk_fail_storage)
                STATUS_FAILURE_TIMEOUT -> Strings.get(R.string.apk_fail_timeout)
                else -> Strings.get(R.string.apk_fail_generic)
            }
        }
    }

    // PackageInstaller.STATUS_* (duplicated so that this file stays pure).
    const val STATUS_FAILURE = 1
    const val STATUS_FAILURE_BLOCKED = 2
    const val STATUS_FAILURE_ABORTED = 3
    const val STATUS_FAILURE_INVALID = 4
    const val STATUS_FAILURE_CONFLICT = 5
    const val STATUS_FAILURE_STORAGE = 6
    const val STATUS_FAILURE_INCOMPATIBLE = 7
    const val STATUS_FAILURE_TIMEOUT = 8
}
