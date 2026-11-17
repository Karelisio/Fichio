package com.karelisio.fichio.apk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Immutable
import com.karelisio.fichio.archive.ProgressCallbacks
import com.karelisio.fichio.archive.copyStream
import com.karelisio.fichio.core.fs.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** An installed app as listed by [InstalledAppsScreen]. */
@Immutable
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystem: Boolean,
    val sourceDir: String,
    val splitSourceDirs: List<String>,
    val totalSize: Long,
    val lastUpdate: Long,
) {
    val hasSplits: Boolean get() = splitSourceDirs.isNotEmpty()
    val exportName: String get() = ApkFormat.exportFileName(label, versionName, hasSplits)
}

object AppExporter {
    /** <primary>/Download/Fichio APK */
    val exportDir: File get() = File(StoragePaths.downloads, "Fichio APK")

    @Suppress("DEPRECATION")
    suspend fun loadInstalled(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        else pm.getInstalledPackages(0)
        packages.mapNotNull { info ->
            val app = info.applicationInfo ?: return@mapNotNull null
            val source = app.sourceDir ?: return@mapNotNull null
            val splits = app.splitSourceDirs?.toList().orEmpty()
            val size = (listOf(source) + splits).sumOf { runCatching { File(it).length() }.getOrDefault(0) }
            InstalledApp(
                packageName = info.packageName,
                label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(info.packageName).ifBlank { info.packageName },
                versionName = info.versionName,
                versionCode = info.longVersionCode,
                isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0,
                sourceDir = source,
                splitSourceDirs = splits,
                totalSize = size,
                lastUpdate = info.lastUpdateTime,
            )
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * Copies the APK of [app] into [destDir]: "Nom_version.apk" for a single APK, or a
     * "Nom_version.apks" bundle (base.apk + split_*.apk) when the app is split.
     * [resolveTarget] decides what to do when the target already exists (null = skip).
     */
    suspend fun export(
        app: InstalledApp,
        destDir: File,
        callbacks: ProgressCallbacks,
        progressBase: Long = 0,
        progressTotal: Long = app.totalSize,
        resolveTarget: suspend (File) -> File? = { it },
    ): File? = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val wanted = File(destDir, app.exportName)
        val target = if (wanted.exists()) resolveTarget(wanted) ?: return@withContext null else wanted
        var done = progressBase
        val onBytes: (Long) -> Unit = { n ->
            done += n
            callbacks.onProgress(done, progressTotal, app.label, 0, 0)
        }
        val tmp = File(destDir, ".${target.name}.part")
        try {
            if (!app.hasSplits) {
                FileInputStream(app.sourceDir).use { input ->
                    FileOutputStream(tmp).use { out -> copyStream(input, out, onBytes, callbacks::checkpoint) }
                }
            } else {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp), 256 * 1024)).use { zos ->
                    val parts = listOf("base.apk" to File(app.sourceDir)) + app.splitSourceDirs.map { File(it).let { f -> f.name to f } }
                    for ((name, file) in parts) {
                        callbacks.checkpoint()
                        zos.putNextEntry(
                            ZipParameters().apply {
                                fileNameInZip = name
                                compressionMethod = CompressionMethod.DEFLATE
                                compressionLevel = CompressionLevel.FASTEST
                                lastModifiedFileTime = file.lastModified()
                            },
                        )
                        FileInputStream(file).use { copyStream(it, zos, onBytes, callbacks::checkpoint) }
                        zos.closeEntry()
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}
