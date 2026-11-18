package com.karelisio.fichio.apk

import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

@Immutable
data class ApkPermission(val name: String, val label: String, val description: String?, val dangerous: Boolean)

@Immutable
data class ApkCertificate(val sha256: String, val subject: String?, val validUntil: Long)

enum class InstallRelation { NOT_INSTALLED, SAME_VERSION, UPDATE, DOWNGRADE }

@Immutable
data class InstalledState(
    val relation: InstallRelation,
    val versionName: String? = null,
    val versionCode: Long = 0,
    /** null when unknown (no signature readable on either side). */
    val signatureMatches: Boolean? = null,
)

/** Something PackageInstaller can stream: a file, or an APK stored inside a bundle. */
sealed interface ApkSource {
    val name: String
    val length: Long
    fun open(): InputStream

    data class OfFile(val file: File) : ApkSource {
        override val name: String get() = file.name
        override val length: Long get() = file.length()
        override fun open(): InputStream = file.inputStream()
    }

    data class InBundle(val bundle: File, val entryName: String, override val length: Long) : ApkSource {
        override val name: String get() = entryName.substringAfterLast('/')
        override fun open(): InputStream {
            val zip = ZipFile(bundle)
            val entry = zip.getEntry(entryName) ?: run { zip.close(); throw java.io.FileNotFoundException(entryName) }
            val input = zip.getInputStream(entry)
            return object : java.io.FilterInputStream(input) {
                override fun close() {
                    try { super.close() } finally { zip.close() }
                }
            }
        }
    }
}

/** Everything the APK screen shows about a file. */
@Immutable
data class ApkDetails(
    val path: String,
    val fileSize: Long,
    val isBundle: Boolean,
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val icon: Drawable?,
    val certificates: List<ApkCertificate>,
    val permissions: List<ApkPermission>,
    val nativeAbis: List<String>,
    val deviceAbis: List<String>,
    val deviceSdk: Int,
    val installed: InstalledState,
    /** All APKs of a bundle (empty for a plain APK). */
    val splits: List<SplitInfo> = emptyList(),
    /** APKs that will be written to the install session. */
    val selectedSplits: List<SplitInfo> = emptyList(),
) {
    val sdkCompatible: Boolean get() = minSdk <= deviceSdk
    val abiCompatible: Boolean get() = SplitSelector.abiCompatible(nativeAbis, deviceAbis)
    val compatible: Boolean get() = sdkCompatible && abiCompatible
    val dangerousCount: Int get() = permissions.count { it.dangerous }

    /** Sources to write into the PackageInstaller session. */
    fun installSources(): List<ApkSource> =
        if (!isBundle) listOf(ApkSource.OfFile(File(path)))
        else selectedSplits.map { ApkSource.InBundle(File(path), it.entryName, it.size) }
}

class ApkParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reads APK / split-APK bundle metadata through PackageManager. */
object ApkInspector {
    private val bundleExtensions = setOf("apks", "xapk", "apkm")

    fun isBundle(path: String) = path.substringAfterLast('.').lowercase() in bundleExtensions

    @Suppress("DEPRECATION")
    private val archiveFlags: Int
        get() = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(archiveFlags.toLong()))
        else pm.getPackageArchiveInfo(apk.path, archiveFlags)

    @Suppress("DEPRECATION")
    fun installedInfo(pm: PackageManager, packageName: String, flags: Int = PackageManager.GET_SIGNING_CERTIFICATES): PackageInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else pm.getPackageInfo(packageName, flags)
        }.getOrNull()

    suspend fun inspect(context: Context, path: String): ApkDetails = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) throw ApkParseException(Strings.get(R.string.apk_err_file_not_found))
        if (isBundle(path)) inspectBundle(context, file) else inspectApk(context, file, file)
    }

    private fun inspectBundle(context: Context, bundle: File): ApkDetails {
        val apkEntries = try {
            ZipFile(bundle).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    .map { it.name to it.size }
                    .toList()
            }
        } catch (e: Exception) {
            throw ApkParseException(Strings.get(R.string.apk_err_unreadable), e)
        }
        if (apkEntries.isEmpty()) throw ApkParseException(Strings.get(R.string.apk_err_no_apk))
        val splits = SplitSelector.classifyAll(apkEntries)
        val base = splits.first { it.kind == SplitKind.BASE }

        // PackageManager needs a real file: copy the base APK to the cache.
        val cacheDir = File(context.cacheDir, "apk_inspect").apply { mkdirs() }
        cacheDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
        val key = Integer.toHexString("${bundle.absolutePath}:${bundle.length()}:${bundle.lastModified()}".hashCode())
        val baseFile = File(cacheDir, "$key-base.apk")
        if (!baseFile.isFile || baseFile.length() != base.size) {
            ApkSource.InBundle(bundle, base.entryName, base.size).open().use { input ->
                baseFile.outputStream().use { input.copyTo(it, 64 * 1024) }
            }
        }
        val details = inspectApk(context, baseFile, bundle)
        val res = context.resources
        val languages = (0 until res.configuration.locales.size()).map { res.configuration.locales[it].language }
        val selected = SplitSelector.select(splits, Build.SUPPORTED_ABIS.toList(), res.displayMetrics.densityDpi, languages)
        val abiFromSplits = splits.filter { it.kind == SplitKind.ABI }.mapNotNull { it.qualifier?.let(SplitSelector::abiToAndroid) }
        return details.copy(
            splits = splits,
            selectedSplits = selected,
            nativeAbis = (details.nativeAbis + abiFromSplits).distinct().sorted(),
        )
    }

    private fun inspectApk(context: Context, apk: File, original: File): ApkDetails {
        val pm = context.packageManager
        val info = archiveInfo(pm, apk) ?: throw ApkParseException(Strings.get(R.string.apk_err_invalid))
        val app: ApplicationInfo = info.applicationInfo ?: throw ApkParseException(Strings.get(R.string.apk_err_no_app))
        // Resources are only reachable once the source dir points at the archive.
        app.sourceDir = apk.absolutePath
        app.publicSourceDir = apk.absolutePath
        val label = runCatching { app.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: info.packageName
        val icon = runCatching { app.loadIcon(pm) }.getOrNull()

        val certs = signaturesOf(info).mapNotNull { certificateOf(it) }.distinctBy { it.sha256 }
        val permissions = info.requestedPermissions.orEmpty().distinct().map { permissionOf(pm, it) }
            .sortedWith(compareBy<ApkPermission>({ !it.dangerous }, { it.label.lowercase() }))
        val abis = runCatching { ZipFile(apk).use { z -> SplitSelector.abisFromEntries(z.entries().asSequence().map { it.name }) } }
            .getOrDefault(emptyList())

        val installed = installedStateOf(pm, info, certs.map { it.sha256 }.toSet())
        return ApkDetails(
            path = original.absolutePath,
            fileSize = original.length(),
            isBundle = original != apk,
            label = label,
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            minSdk = app.minSdkVersion,
            targetSdk = app.targetSdkVersion,
            icon = icon,
            certificates = certs,
            permissions = permissions,
            nativeAbis = abis,
            deviceAbis = Build.SUPPORTED_ABIS.toList(),
            deviceSdk = Build.VERSION.SDK_INT,
            installed = installed,
        )
    }

    @Suppress("DEPRECATION")
    fun signaturesOf(info: PackageInfo): List<Signature> {
        val si = info.signingInfo
        val fromSigning = when {
            si == null -> null
            si.hasMultipleSigners() -> si.apkContentsSigners?.toList()
            else -> si.signingCertificateHistory?.toList()
        }
        return fromSigning?.takeIf { it.isNotEmpty() } ?: info.signatures?.toList().orEmpty()
    }

    fun certificateOf(sig: Signature): ApkCertificate? = runCatching {
        val bytes = sig.toByteArray()
        val fp = ApkFormat.fingerprint(ApkFormat.sha256(bytes))
        val x509 = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        }.getOrNull()
        ApkCertificate(fp, x509?.subjectX500Principal?.name, x509?.notAfter?.time ?: 0)
    }.getOrNull()

    private fun permissionOf(pm: PackageManager, name: String): ApkPermission {
        val pi = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull()
        val fallback = name.substringAfterLast('.').lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        val label = pi?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() }
            ?.takeIf { it.isNotBlank() && it != name }
            ?.replaceFirstChar { it.uppercase() } ?: fallback
        val description = pi?.let { runCatching { it.loadDescription(pm)?.toString() }.getOrNull() }?.takeIf { it.isNotBlank() }
        val dangerous = pi?.protection == PermissionInfo.PROTECTION_DANGEROUS
        return ApkPermission(name, label, description, dangerous)
    }

    private fun installedStateOf(pm: PackageManager, archive: PackageInfo, archiveCerts: Set<String>): InstalledState {
        val inst = installedInfo(pm, archive.packageName) ?: return InstalledState(InstallRelation.NOT_INSTALLED)
        val installedCerts = signaturesOf(inst).mapNotNull { certificateOf(it)?.sha256 }.toSet()
        val match = if (installedCerts.isEmpty() || archiveCerts.isEmpty()) null else installedCerts.any { it in archiveCerts }
        val relation = when {
            archive.longVersionCode > inst.longVersionCode -> InstallRelation.UPDATE
            archive.longVersionCode < inst.longVersionCode -> InstallRelation.DOWNGRADE
            else -> InstallRelation.SAME_VERSION
        }
        return InstalledState(relation, inst.versionName, inst.longVersionCode, match)
    }
}
