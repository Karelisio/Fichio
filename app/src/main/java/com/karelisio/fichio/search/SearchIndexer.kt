package com.karelisio.fichio.search

import android.content.Context
import com.karelisio.fichio.core.fs.FileSystems
import com.karelisio.fichio.data.db.FichioDatabase
import kotlinx.coroutines.CoroutineScope

/** Local Room FTS index: full scans in WorkManager + incremental updates on [FileSystems.changes]. CONTRACT STUB. */
class SearchIndexer(
    private val context: Context,
    private val db: FichioDatabase,
    private val fs: FileSystems,
    private val scope: CoroutineScope,
) {
    /** Starts listening to in-app changes and schedules the periodic full scan. */
    fun start() {}
    /** Full rescan now (used on first launch after permission grant). */
    fun requestFullScan() {}
}
