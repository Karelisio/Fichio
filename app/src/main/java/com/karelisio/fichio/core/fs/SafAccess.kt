package com.karelisio.fichio.core.fs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Write access of a removable volume, as seen by the browser banner. */
enum class VolumeWriteAccess {
    /** Not probed yet. */
    UNKNOWN,

    /** java.io writes work: nothing to do. */
    DIRECT,

    /** java.io cannot write and no tree is granted: the banner offers « Autoriser l'écriture ». */
    READ_ONLY,

    /** Writes go through the granted SAF tree ([SafBackend]). */
    GRANTED,
}

/**
 * Removable volumes Android does not let Fichio write to with java.io (common on USB keys and
 * some SD cards even with « Accès à tous les fichiers »): probes direct writes, asks for a SAF
 * tree on the volume root (ACTION_OPEN_DOCUMENT_TREE, persisted permission) and provides the
 * [SafBackend] that [FileSystems.backendFor] routes the volume's paths to.
 *
 * The persisted URI permissions are the source of truth: nothing else is stored.
 */
class SafAccess(private val context: Context, private val volumes: VolumeRepository) {
    private class Grant(val backend: SafBackend, val wholeVolume: Boolean)

    @Volatile private var grants: List<Grant> = emptyList()
    private val probed = ConcurrentHashMap<String, Boolean>()

    private val _access = MutableStateFlow<Map<String, VolumeWriteAccess>>(emptyMap())
    /** Volume root → access, for the removable volumes probed so far. */
    val access: StateFlow<Map<String, VolumeWriteAccess>> = _access.asStateFlow()

    init {
        runCatching { reloadGrants() }
    }

    /** Backend for [path] when it lies in a granted tree of a removable volume; null otherwise (java.io). */
    fun backendFor(path: String): FileBackend? {
        val list = grants
        if (list.isEmpty()) return null
        return list.firstOrNull { it.wholeVolume && it.backend.covers(path) }?.backend
            ?: list.firstOrNull { it.backend.covers(path) }?.backend
    }

    /** The removable volume containing [path], if any. */
    fun removableVolumeOf(path: String): Volume? =
        volumes.volumes.value.filter { it.isRemovable && !it.isPrimary && (path == it.path || path.startsWith(it.path + "/")) }
            .maxByOrNull { it.path.length }

    /** Probes the volume (creates and deletes a hidden file) and publishes its [VolumeWriteAccess]. */
    suspend fun check(volume: Volume): VolumeWriteAccess = withContext(Dispatchers.IO) {
        // The card may have been inserted (or the permission revoked) since the last look.
        runCatching { reloadGrants() }
        val direct = probed[volume.path] ?: probeDirect(volume.path).also { probed[volume.path] = it }
        val state = when {
            direct -> VolumeWriteAccess.DIRECT
            grants.any { it.backend.volumeRoot == volume.path } -> VolumeWriteAccess.GRANTED
            else -> VolumeWriteAccess.READ_ONLY
        }
        _access.update { it + (volume.path to state) }
        state
    }

    /** Forgets the probe of [volumeRoot] (e.g. after a write failed there). */
    fun invalidate(volumeRoot: String) {
        probed.remove(volumeRoot)
    }

    private fun probeDirect(root: String): Boolean {
        val probe = File(root, ".fichio-write-test-${System.nanoTime()}")
        return try {
            probe.createNewFile() && probe.exists()
        } catch (_: Exception) {
            false
        } finally {
            runCatching { probe.delete() }
        }
    }

    /** Volume id of the provider for this volume (filesystem UUID). */
    fun volumeId(volume: Volume): String =
        runCatching { context.getSystemService<StorageManager>()?.getStorageVolume(File(volume.path))?.uuid }.getOrNull()
            ?: SafPaths.fallbackVolumeId(volume.path)

    /** Opens the system folder picker directly on the volume root. */
    fun openTreeIntent(volume: Volume): Intent {
        val fromVolume = runCatching {
            context.getSystemService<StorageManager>()?.getStorageVolume(File(volume.path))?.createOpenDocumentTreeIntent()
        }.getOrNull()
        val intent = fromVolume ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildRootUri(SafPaths.AUTHORITY, volumeId(volume)),
        )
        return intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
    }

    enum class GrantResult { OK, WRONG_VOLUME, NOT_ROOT, FAILED }

    /**
     * Result of the picker: keeps the permission when [uri] is a tree of [volume] (preferably its
     * root). A sub-folder is accepted too (writes limited to it) but reported as [GrantResult.NOT_ROOT].
     */
    suspend fun onTreePicked(volume: Volume, uri: Uri): GrantResult = withContext(Dispatchers.IO) {
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return@withContext GrantResult.FAILED
        if (uri.authority != SafPaths.AUTHORITY || !SafPaths.isTreeOf(volumeId(volume), treeDocId)) {
            return@withContext GrantResult.WRONG_VOLUME
        }
        val ok = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (!ok) return@withContext GrantResult.FAILED
        reloadGrants()
        check(volume)
        if (SafPaths.isVolumeRoot(treeDocId)) GrantResult.OK else GrantResult.NOT_ROOT
    }

    /** Rebuilds the grants from the persisted permissions (also drops the revoked ones). */
    fun reloadGrants() {
        val vols = volumes.volumes.value.ifEmpty { volumes.refresh() }.filter { it.isRemovable && !it.isPrimary }
        val perms = context.contentResolver.persistedUriPermissions.filter { it.isWritePermission && it.uri.authority == SafPaths.AUTHORITY }
        grants = perms.mapNotNull { p ->
            val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(p.uri) }.getOrNull() ?: return@mapNotNull null
            val vol = vols.firstOrNull { SafPaths.isTreeOf(volumeId(it), treeDocId) } ?: return@mapNotNull null
            Grant(SafBackend(context, vol.path, volumeId(vol), p.uri), SafPaths.isVolumeRoot(treeDocId))
        }
    }
}
