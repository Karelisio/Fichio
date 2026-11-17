package com.karelisio.fichio.shizuku

import androidx.annotation.Keep
import java.io.File

/**
 * Shevery/Shizuku UserService: runs in a process managed by the Shizuku/Shevery
 * server so it can list, read, copy and modify Android/data and Android/obb,
 * which are otherwise unreachable on Android 11+.
 *
 * Since Shizuku API v13 the UserService is no longer an Android [android.app.Service]:
 * it directly extends the generated AIDL stub and is instantiated by the server
 * through reflection. The public no-arg constructor is therefore required.
 * If Shevery is not running the service is never instantiated and the rest of
 * Fichio keeps working normally.
 */
@Keep
class FichioUserService : IUserService.Stub() {

    override fun destroy() {
        // Reserved destroy hook. No persistent state to release.
    }

    override fun ping(): String = "pong"

    override fun listFiles(path: String): Array<String> =
        runCatching { File(path).list() ?: emptyArray() }.getOrDefault(emptyArray())

    override fun isDirectory(path: String): Boolean = File(path).isDirectory
    override fun exists(path: String): Boolean = File(path).exists()
    override fun length(path: String): Long = File(path).length()
    override fun lastModified(path: String): Long = File(path).lastModified()

    override fun copy(from: String, to: String): Boolean =
        runCatching { File(from).copyTo(File(to), overwrite = true); true }.getOrDefault(false)

    override fun move(from: String, to: String): Boolean =
        runCatching { File(from).renameTo(File(to)) }.getOrDefault(false)

    override fun delete(path: String): Boolean =
        runCatching { File(path).deleteRecursively() }.getOrDefault(false)

    override fun mkdir(path: String): Boolean =
        runCatching { File(path).mkdirs() }.getOrDefault(false)

    override fun rename(from: String, to: String): Boolean =
        runCatching { File(from).renameTo(File(to)) }.getOrDefault(false)
}
