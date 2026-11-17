package com.karelisio.fichio.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Immutable
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.map.Mapper
import coil3.memory.MemoryCache
import coil3.request.Options
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.karelisio.fichio.core.fs.FileSystems
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.core.model.FileTypes
import okio.Buffer
import okio.buffer
import okio.source
import java.io.File

/** Coil model for a file thumbnail. Keyed by path + mtime so edits invalidate the cache. */
@Immutable
data class Thumb(val path: String, val type: FileType, val lastModified: Long) {
    constructor(entry: FileEntry) : this(entry.path, entry.type, entry.lastModified)

    companion object {
        fun supports(type: FileType) = type == FileType.IMAGE || type == FileType.VIDEO ||
            type == FileType.AUDIO || type == FileType.APK
    }
}

private class ThumbKeyer : Keyer<Thumb> {
    override fun key(data: Thumb, options: Options) = "${data.path}:${data.lastModified}"
}

/** Local images/videos map to File so that Coil's own fetchers + VideoFrameDecoder handle them. */
private class ThumbMapper : Mapper<Thumb, File> {
    override fun map(data: Thumb, options: Options): File? =
        if ((data.type == FileType.IMAGE || data.type == FileType.VIDEO) && !StoragePaths.isRestricted(data.path)) File(data.path)
        else null
}

/** Audio covers, APK icons and Android/data images (through Shevery). */
private class ThumbFetcher(
    private val data: Thumb,
    private val options: Options,
    private val context: Context,
    private val fs: FileSystems,
) : Fetcher {
    override suspend fun fetch(): FetchResult? = when (data.type) {
        FileType.AUDIO -> audioCover()
        FileType.APK -> apkIcon()
        FileType.IMAGE -> restrictedImage()
        else -> null
    }

    private fun audioCover(): FetchResult? {
        if (StoragePaths.isRestricted(data.path)) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(data.path)
            val bytes = retriever.embeddedPicture ?: return null
            SourceFetchResult(
                source = ImageSource(Buffer().write(bytes), options.fileSystem),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun apkIcon(): FetchResult? {
        if (StoragePaths.isRestricted(data.path) || !data.path.endsWith(".apk", true)) return null
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(data.path, PackageManager.PackageInfoFlags.of(0)) ?: return null
        val app = info.applicationInfo ?: return null
        app.sourceDir = data.path
        app.publicSourceDir = data.path
        val icon = app.loadIcon(pm) ?: return null
        return ImageFetchResult(image = icon.asImage(), isSampled = false, dataSource = DataSource.DISK)
    }

    private fun restrictedImage(): FetchResult? {
        val backend = fs.backendOrNull(data.path) ?: return null
        val stream = runCatching { backend.openInput(data.path) }.getOrNull() ?: return null
        return SourceFetchResult(
            source = ImageSource(stream.source().buffer(), options.fileSystem),
            mimeType = FileTypes.mimeOfName(data.path),
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val context: Context, private val fs: FileSystems) : Fetcher.Factory<Thumb> {
        override fun create(data: Thumb, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (Thumb.supports(data.type)) ThumbFetcher(data, options, context, fs) else null
    }
}

object FichioImageLoader {
    fun create(context: PlatformContext, fs: FileSystems): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(ThumbKeyer())
                add(ThumbMapper())
                add(ThumbFetcher.Factory(context, fs))
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("thumbs"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(180)
            .build()
}
