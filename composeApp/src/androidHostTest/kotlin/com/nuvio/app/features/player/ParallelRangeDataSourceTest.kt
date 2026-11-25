package com.nuvio.app.features.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.random.Random

/**
 * The mobile port of the TV app's parallel downloader: reads must return the file byte for byte,
 * and chunk memory (SharedMemory on devices, a poisoning fake here) must be freed exactly once and
 * never while a reader holds it, since reading an unmapped SharedMemory chunk would crash the app.
 */
@RunWith(RobolectricTestRunner::class)
class ParallelRangeDataSourceTest {

    private class FakeChunkMemory {
        val live: MutableSet<ChunkMemory> = ConcurrentHashMap.newKeySet()
        val allocated = AtomicInteger()
        val freed = AtomicInteger()
        val badFrees = AtomicInteger()

        fun allocate(size: Int): ChunkMemory {
            val bytes = ByteBuffer.allocate(size)
            lateinit var memory: ChunkMemory
            memory = ChunkMemory(bytes) {
                if (!live.remove(memory)) {
                    badFrees.incrementAndGet()
                } else {
                    for (i in 0 until bytes.capacity()) bytes.put(i, POISON)
                    freed.incrementAndGet()
                }
            }
            allocated.incrementAndGet()
            live.add(memory)
            return memory
        }
    }

    private val memory = FakeChunkMemory()
    private val server = MockWebServer()
    private val file = Random(7).nextBytes(2 * 1024 * 1024)
    private val requests = AtomicInteger()
    private val opened = ConcurrentHashMap.newKeySet<ParallelRangeDataSource>()
    private lateinit var allocateBefore: (Int) -> ChunkMemory?

    @Before
    fun setUp() {
        allocateBefore = ParallelRangeDataSource.allocateChunk
        ParallelRangeDataSource.allocateChunk = memory::allocate
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        teardownSessions()
        drainPool()
        ParallelRangeDataSource.allocateChunk = allocateBefore
        server.shutdown()
    }

    private fun serve(acceptRanges: Boolean = true) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                val range = request.getHeader("Range")
                if (range == null || !acceptRanges) {
                    return MockResponse().setResponseCode(200).setBody(Buffer().write(file))
                }
                val parts = range.removePrefix("bytes=").split("-")
                val from = parts[0].toInt()
                val to = minOf(parts.getOrNull(1)?.toIntOrNull() ?: (file.size - 1), file.size - 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes $from-$to/${file.size}")
                    .setBody(Buffer().write(file, from, to - from + 1))
            }
        }
        server.start()
    }

    private fun source(continuation: Boolean = true) = ParallelRangeDataSource(
        OkHttpDataSource.Factory(OkHttpClient()),
        parallelConnections = 2,
        chunkSize = CHUNK,
        allowContinuationReopen = continuation,
    ).also { opened.add(it) }

    private fun teardownSessions() = ParallelRangeDataSource.releaseRetainedSession()

    private fun drainPool() = ParallelRangeDataSource.drainIdleBuffers(CHUNK)

    private val fileUri by lazy { Uri.parse(server.url("/file").toString()) }

    private fun spec(position: Long) = DataSpec.Builder().setUri(fileUri).setPosition(position).build()

    private fun readAll(dataSource: DataSource, position: Long = 0L): ByteArray {
        val out = ByteArrayOutputStream()
        dataSource.open(DataSpec.Builder().setUri(fileUri).setPosition(position).build())
        val scratch = ByteArray(7_919)
        while (true) {
            val read = dataSource.read(scratch, 0, scratch.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(scratch, 0, read)
        }
        dataSource.close()
        return out.toByteArray()
    }

    @Test
    fun `reads the whole file over ranged parallel requests`() {
        serve()
        val bytes = readAll(source())
        assertArrayEquals(file, bytes)
        assertTrue("expected several ranged requests, got ${requests.get()}", requests.get() > 2)
        assertAllMemoryReturned()
    }

    @Test
    fun `reads from a seek position to the end`() {
        serve()
        val start = 3L * CHUNK + 1_234L
        val bytes = readAll(source(), position = start)
        assertArrayEquals(file.copyOfRange(start.toInt(), file.size), bytes)
        assertAllMemoryReturned()
    }

    @Test
    fun `falls back to one connection when the server ignores ranges`() {
        serve(acceptRanges = false)
        assertArrayEquals(file, readAll(source()))
    }

    @Test
    fun `routing sends only the source url through parallel connections`() {
        serve()
        val sourceUrl = server.url("/file").toString()
        val plainOpens = AtomicInteger()
        val plainFactory = DataSource.Factory {
            val upstream = OkHttpDataSource.Factory(OkHttpClient()).createDataSource()
            object : DataSource by upstream {
                override fun open(dataSpec: DataSpec): Long {
                    plainOpens.incrementAndGet()
                    return upstream.open(dataSpec)
                }
            }
        }
        val routed = plainFactory.withParallelConnections(
            sourceUrl = sourceUrl,
            plan = ParallelPlaybackPlan(connections = 2, chunkSizeKb = (CHUNK / 1024).toInt(), automatic = true),
            requestHeaders = emptyMap(),
        )
        assertArrayEquals(file, readAll(routed.createDataSource()))
        assertEquals("the source url must not use the plain path", 0, plainOpens.get())

        val subtitle = routed.createDataSource()
        subtitle.open(DataSpec.Builder().setUri(Uri.parse(server.url("/subtitle.srt").toString())).build())
        subtitle.close()
        assertEquals("other urls keep the plain path", 1, plainOpens.get())
        assertTrue(plainFactory.withParallelConnections(sourceUrl, null, emptyMap()) === plainFactory)
    }

    // Reads like the player: compares every byte with the file and reopens at the same position
    // after a failed read.
    private inner class Reader(val source: ParallelRangeDataSource) {
        var position = 0L
        var mismatches = 0
        var reopens = 0
        private var isOpen = false
        private val scratch = ByteArray(2_000)

        fun readTo(until: Long, pace: Boolean = false, deadlineMs: Long = System.currentTimeMillis() + 60_000L) {
            while (position < until && System.currentTimeMillis() < deadlineMs) {
                if (!isOpen) {
                    try {
                        source.open(spec(position))
                        isOpen = true
                    } catch (_: IOException) {
                        source.close()
                        reopens++
                        continue
                    }
                }
                val wanted = minOf(scratch.size.toLong(), until - position).toInt()
                val read = try {
                    source.read(scratch, 0, wanted)
                } catch (_: IOException) {
                    C.RESULT_END_OF_INPUT
                }
                if (read == C.RESULT_END_OF_INPUT || read == 0) {
                    source.close()
                    isOpen = false
                    reopens++
                    continue
                }
                for (i in 0 until read) {
                    if (scratch[i] != file[(position + i).toInt()]) {
                        mismatches++
                        break
                    }
                }
                position += read
                if (pace) LockSupport.parkNanos(20_000L)
            }
        }

        fun close() {
            source.close()
            isOpen = false
        }

        fun stats() = "mismatches=$mismatches reopens=$reopens allocated=${memory.allocated.get()} freed=${memory.freed.get()}"
    }

    private fun pooledBuffer(chunk: ChunkMemory): Any =
        Class.forName("com.nuvio.app.features.player.ParallelRangeDataSource\$PooledBuffer")
            .declaredConstructors.single().apply { isAccessible = true }.newInstance(chunk, chunk.buffer)

    private fun call(target: Any, name: String, vararg args: Any?): Any? = target.javaClass.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }
        .apply { isAccessible = true }.invoke(target, *args)

    private fun releaseSessionBuffer(buffer: Any, poolCap: Int) {
        call(ParallelRangeDataSource.Companion, "releaseSessionBuffer", buffer, CHUNK, poolCap)
    }

    private fun pooled(): Int =
        (ParallelRangeDataSource::class.java.getDeclaredField("globalBufferPool").apply { isAccessible = true }
            .get(null) as Map<*, *>).values.sumOf { (it as Collection<*>).size }

    private fun assertAllMemoryReturned() {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < end) {
            teardownSessions()
            drainPool()
            if (memory.freed.get() == memory.allocated.get()) break
            Thread.sleep(10)
        }
        assertEquals("chunk memory freed twice or never allocated here", 0, memory.badFrees.get())
        assertEquals("every chunk freed exactly once", memory.allocated.get(), memory.freed.get())
    }

    @Test
    fun `releasing a buffer nobody reads frees it once and refuses later leases`() {
        val buffer = pooledBuffer(memory.allocate(CHUNK.toInt()))
        releaseSessionBuffer(buffer, 0)
        releaseSessionBuffer(buffer, 0)
        releaseSessionBuffer(buffer, 4)
        assertEquals(1, memory.freed.get())
        assertEquals(0, memory.badFrees.get())
        assertFalse(call(buffer, "lease") as Boolean)
        assertEquals(0, pooled())
    }

    @Test
    fun `release requested during a read waits for the reader`() {
        val buffer = pooledBuffer(memory.allocate(CHUNK.toInt()))
        assertTrue(call(buffer, "lease") as Boolean)
        assertTrue(call(buffer, "lease") as Boolean)
        releaseSessionBuffer(buffer, 0)
        assertEquals(0, memory.freed.get())
        assertFalse("no new reader once release is requested", call(buffer, "lease") as Boolean)
        call(buffer, "endLease")
        assertEquals(0, memory.freed.get())
        call(buffer, "endLease")
        assertEquals(1, memory.freed.get())
        releaseSessionBuffer(buffer, 0)
        assertEquals(1, memory.freed.get())
        assertEquals(0, memory.badFrees.get())
    }

    @Test
    fun `concurrent readers and one release free the memory exactly once and never under a reader`() {
        repeat(300) { round ->
            val chunk = memory.allocate(4_096)
            val bytes = chunk.buffer
            for (i in 0 until bytes.capacity()) bytes.put(i, 1)
            val buffer = pooledBuffer(chunk)
            val sawPoison = AtomicInteger()
            val failure = AtomicReference<Throwable>()
            val start = CountDownLatch(1)
            val readers = (0 until 4).map {
                Thread {
                    try {
                        start.await()
                        while (call(buffer, "lease") as Boolean) {
                            if (bytes.get(Random.nextInt(bytes.capacity())) == POISON) sawPoison.incrementAndGet()
                            call(buffer, "endLease")
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }.apply { start() }
            }
            start.countDown()
            Thread.sleep(0, Random.nextInt(200_000))
            releaseSessionBuffer(buffer, if (round % 2 == 0) 0 else 4)
            readers.forEach { it.join(5_000) }
            failure.get()?.let { throw it }
            assertEquals("reader saw freed memory in round $round", 0, sawPoison.get())
        }
        drainPool()
        assertEquals(300, memory.freed.get())
        assertEquals(0, memory.badFrees.get())
    }

    @Test
    fun `paced reader stays correct while sessions are torn down repeatedly`() {
        serve()
        val done = AtomicBoolean(false)
        val chaos = Thread {
            while (!done.get()) {
                Thread.sleep(Random.nextLong(5, 30))
                teardownSessions()
                drainPool()
            }
        }
        chaos.start()
        val reader = Reader(source())
        try {
            reader.readTo(file.size.toLong(), pace = true)
        } finally {
            done.set(true)
            chaos.join(5_000)
            reader.close()
        }
        assertEquals("bytes read from freed or reused chunk memory, ${reader.stats()}", 0, reader.mismatches)
        assertEquals(file.size.toLong(), reader.position)
        assertAllMemoryReturned()
    }

    private companion object {
        const val CHUNK = 64L * 1024L
        const val POISON: Byte = 0x5A
    }
}
