package com.nuvio.app.features.player

import android.os.Build
import android.os.SharedMemory
import java.nio.ByteBuffer

/** Starting values for parallel connections, matching the TV app's Parallel Connections toggle. */
internal object ParallelConnectionDefaults {
    const val CONNECTIONS = 2
    const val MIN_CONNECTIONS = 2
    const val MAX_CONNECTIONS = 4
    const val CHUNK_SIZE_KB = 16 * 1024
    const val MIN_CHUNK_SIZE_MB = 4
    const val MAX_CHUNK_SIZE_MB = 32

    /**
     * Parallel chunks need SharedMemory (Android 8.1+) to stay off the Java heap. Older devices keep
     * the single connection rather than put several 16 MB chunks on a heap without largeHeap.
     */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
}

/** One chunk buffer and the call that gives its memory back. */
internal class ChunkMemory(val buffer: ByteBuffer, private val onFree: () -> Unit) {
    private val freed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun free() {
        if (freed.compareAndSet(false, true)) onFree()
    }
}

/** A SharedMemory-backed chunk, or null where SharedMemory is missing or the mapping fails. */
internal fun allocateSharedChunk(size: Int): ChunkMemory? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
    return runCatching {
        val memory = SharedMemory.create("nuvio_parallel_chunk", size)
        val mapped = try {
            memory.mapReadWrite()
        } catch (error: Throwable) {
            memory.close()
            throw error
        }
        ChunkMemory(mapped) {
            SharedMemory.unmap(mapped)
            memory.close()
        }
    }.getOrNull()
}
