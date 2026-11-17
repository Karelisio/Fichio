package com.karelisio.fichio.archive

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karelisio.fichio.ui.navigation.Opener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** State of the archive browser; survives configuration changes and closes the reader on exit. */
class ArchiveViewModel(private val app: Context, val path: String) : ViewModel() {
    sealed interface Load {
        data object Loading : Load
        /** The listing itself is encrypted (7z / rar with encrypted headers). */
        data object Locked : Load
        data object Ready : Load
        data class Failed(val message: String) : Load
    }

    data class PasswordPrompt(val wrong: Boolean = false, val busy: Boolean = false)

    val file = File(path)
    var load by mutableStateOf<Load>(Load.Loading); private set
    var tree by mutableStateOf<ArchiveTree?>(null); private set
    var format by mutableStateOf<ArchiveFormat?>(null); private set
    var encrypted by mutableStateOf(false); private set
    var dir by mutableStateOf(""); private set
    var selection by mutableStateOf<Set<String>>(emptySet()); private set
    var prompt by mutableStateOf<PasswordPrompt?>(null); private set
    /** Name of the file being extracted to the cache to be opened, with its progress. */
    var opening by mutableStateOf<String?>(null); private set
    var openingProgress by mutableFloatStateOf(0f); private set

    /** Password once verified (null when none needed / not given yet). */
    var password: CharArray? = null; private set

    private var reader: ArchiveReader? = null
    private var pending: (() -> Unit)? = null
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    init {
        reload(null)
    }

    private fun reload(pw: CharArray?) = viewModelScope.launch {
        load = Load.Loading
        try {
            val r = withContext(Dispatchers.IO) { Archives.open(file, pw).also { it.tree } }
            swap(r, pw)
        } catch (e: PasswordRequiredException) {
            load = Load.Locked
            prompt = PasswordPrompt()
        } catch (e: WrongPasswordException) {
            load = Load.Locked
            prompt = PasswordPrompt(wrong = true)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            load = Load.Failed(t.archiveMessage())
        }
    }

    private fun swap(r: ArchiveReader, pw: CharArray?) {
        reader?.close()
        reader = r
        tree = r.tree
        format = r.format
        encrypted = r.isEncrypted || pw != null
        if (pw != null) password = pw
        if (tree?.get(dir)?.isDirectory != true) dir = ""
        load = Load.Ready
    }

    fun submitPassword(pw: CharArray) {
        prompt = (prompt ?: PasswordPrompt()).copy(busy = true)
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { Archives.open(file, pw).also { it.tree; it.verifyPassword() } }
                swap(r, pw)
                prompt = null
                pending?.invoke()
                pending = null
            } catch (e: WrongPasswordException) {
                prompt = PasswordPrompt(wrong = true)
            } catch (e: PasswordRequiredException) {
                prompt = PasswordPrompt(wrong = true)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                prompt = null
                _messages.tryEmit(t.archiveMessage())
            }
        }
    }

    fun askPassword() {
        prompt = PasswordPrompt()
    }

    fun dismissPassword() {
        prompt = null
        pending = null
    }

    /** Runs [action] once the archive is readable (asks for the password first if needed). */
    fun withPassword(action: () -> Unit) {
        if (encrypted && password == null) {
            pending = action
            prompt = PasswordPrompt()
        } else action()
    }

    fun navigate(to: String) {
        dir = to
    }

    /** Goes one folder up; false when already at the root. */
    fun up(): Boolean {
        if (dir.isEmpty()) return false
        dir = dir.substringBeforeLast('/', "")
        return true
    }

    fun toggle(item: ArchiveItem) {
        selection = if (item.path in selection) selection - item.path else selection + item.path
    }

    fun clearSelection() {
        selection = emptySet()
    }

    fun selectAll() {
        val all = tree?.children(dir)?.map { it.path }?.toSet().orEmpty()
        selection = if (selection.containsAll(all)) emptySet() else all
    }

    /** Extracts one file to the cache and hands it to another app. */
    fun openItem(item: ArchiveItem): Unit = withPassword {
        val r = reader ?: return@withPassword
        viewModelScope.launch {
            opening = item.name
            openingProgress = 0f
            try {
                val target = File(app.cacheDir, "archive_open/${Integer.toHexString(path.hashCode())}")
                val out = withContext(Dispatchers.IO) {
                    target.deleteRecursively()
                    r.extractTo(item, target, object : ExtractCallbacks {
                        override fun onProgress(doneBytes: Long, totalBytes: Long, currentName: String, doneItems: Int, totalItems: Int) {
                            if (totalBytes > 0) openingProgress = doneBytes.toFloat() / totalBytes
                        }
                    })
                }
                Opener.openExternally(app, out.absolutePath)
            } catch (e: WrongPasswordException) {
                password = null
                pending = { openItem(item) }
                prompt = PasswordPrompt(wrong = true)
            } catch (e: PasswordRequiredException) {
                password = null
                pending = { openItem(item) }
                prompt = PasswordPrompt()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _messages.tryEmit(t.archiveMessage())
            } finally {
                opening = null
            }
        }
    }

    override fun onCleared() {
        reader?.close()
        reader = null
    }
}
