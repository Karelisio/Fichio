package com.karelisio.fichio.core.fs

import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.compose.runtime.Immutable
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class Volume(
    val path: String,
    val label: String,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
    val isUsb: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction: Float get() = if (totalBytes <= 0) 0f else usedBytes.toFloat() / totalBytes
}

/** Well-known paths and volume discovery. */
object StoragePaths {
    val primary: String by lazy { Environment.getExternalStorageDirectory().absolutePath }
    val androidData: String get() = "$primary/Android/data"
    val androidObb: String get() = "$primary/Android/obb"
    val downloads: String get() = "$primary/${Environment.DIRECTORY_DOWNLOADS}"
    /** Hidden Fichio folder at the root of the primary volume (trash, temp, extracted APKs). */
    val fichioDir: String get() = "$primary/.Fichio"
    val trashDir: String get() = "$fichioDir/trash"

    /** True for Android/data and Android/obb (and anything below), which need Shevery. */
    fun isRestricted(path: String): Boolean {
        val p = path.trimEnd('/')
        return p == androidData || p.startsWith("$androidData/") || p == androidObb || p.startsWith("$androidObb/")
    }

    fun isInsideFichioDir(path: String) = path == fichioDir || path.startsWith("$fichioDir/")

    fun displayName(path: String, volumes: List<Volume>): String {
        volumes.firstOrNull { it.path == path }?.let { return it.label }
        return path.substringAfterLast('/').ifEmpty { "/" }
    }

    fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

    fun parent(path: String): String = path.substringBeforeLast('/', "/").ifEmpty { "/" }

    fun name(path: String): String = path.substringAfterLast('/')

    /** Breadcrumb segments from the volume root down to [path]. */
    fun segments(path: String, volumes: List<Volume>): List<Pair<String, String>> {
        val vol = volumes.filter { path == it.path || path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }
        val root = vol?.path ?: "/"
        val out = mutableListOf(root to (vol?.label ?: "/"))
        val rest = path.removePrefix(root).trim('/')
        if (rest.isNotEmpty()) {
            var acc = root.trimEnd('/')
            rest.split('/').forEach { seg ->
                acc = "$acc/$seg"
                out += acc to seg
            }
        }
        return out
    }
}

/** Observes mounted volumes (internal + USB OTG / SD) with their space. */
class VolumeRepository(private val context: Context) {
    private val _volumes = MutableStateFlow<List<Volume>>(emptyList())
    val volumes: StateFlow<List<Volume>> = _volumes.asStateFlow()

    fun refresh(): List<Volume> {
        val sm = context.getSystemService<StorageManager>()
        val list = sm?.storageVolumes.orEmpty().mapNotNull { v ->
            val dir = v.directory ?: return@mapNotNull null
            if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
            val stat = runCatching { StatFs(dir.absolutePath) }.getOrNull()
            val desc = runCatching { v.getDescription(context) }.getOrNull().orEmpty()
            val isUsb = v.isRemovable && (desc.contains("USB", true) || runCatching {
                // StorageVolume.isUsb() is hidden; fall back on the description heuristic.
                v.javaClass.getMethod("isUsb").invoke(v) as Boolean
            }.getOrDefault(false))
            Volume(
                path = dir.absolutePath,
                label = when {
                    v.isPrimary -> Strings.get(R.string.storage_internal)
                    isUsb -> desc.ifEmpty { Strings.get(R.string.storage_usb) }
                    else -> desc.ifEmpty { Strings.get(R.string.storage_sd) }
                },
                isPrimary = v.isPrimary,
                isRemovable = v.isRemovable,
                isUsb = isUsb,
                totalBytes = stat?.totalBytes ?: 0,
                freeBytes = stat?.availableBytes ?: 0,
            )
        }.ifEmpty {
            val stat = runCatching { StatFs(StoragePaths.primary) }.getOrNull()
            listOf(Volume(StoragePaths.primary, Strings.get(R.string.storage_internal), true, false, false, stat?.totalBytes ?: 0, stat?.availableBytes ?: 0))
        }.sortedByDescending { it.isPrimary }
        _volumes.value = list
        return list
    }

    val primary: Volume? get() = _volumes.value.firstOrNull { it.isPrimary }
}
