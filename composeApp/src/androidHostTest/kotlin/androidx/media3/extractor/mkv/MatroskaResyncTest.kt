package androidx.media3.extractor.mkv

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.app.features.player.withMatroskaResync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/** Media3's own MatroskaExtractor, with and without the resyncing reader, on a damaged file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MatroskaResyncTest {

    @Test
    fun `stock Media3 ends playback on an element size beyond int range`() {
        val extractor = MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED)
        assertThrows(ArrayIndexOutOfBoundsException::class.java) { extract(extractor, damagedFile()) }
    }

    @Test
    fun `the resyncing reader keeps the blocks before and after the damage`() {
        val extractor = MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED)
        assertTrue(MatroskaResync.install(extractor))
        assertEquals(listOf(0L, 9_000L), extract(extractor, damagedFile()))
    }

    @Test
    fun `an intact file reads the same with the resyncing reader`() {
        val stock = extract(MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED), intactFile())
        val patched = MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED)
        assertTrue(MatroskaResync.install(patched))
        assertEquals(listOf(0L, 9_000L), stock)
        assertEquals(stock, extract(patched, intactFile()))
    }

    @Test
    fun `install is idempotent and covers subclasses`() {
        val subclass = object : MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED) {}
        assertTrue(MatroskaResync.install(subclass))
        assertTrue(MatroskaResync.install(subclass))
        assertTrue(readerOf(subclass) is ResyncingEbmlReader)
        assertEquals(listOf(0L, 9_000L), extract(subclass, damagedFile()))
    }

    @Test
    fun `the factory wrapper installs the reader in the Matroska extractor it makes`() {
        val extractors = DefaultExtractorsFactory().withMatroskaResync().createExtractors()
        val matroska = extractors.map { it.underlyingImplementation }.filterIsInstance<MatroskaExtractor>()
        assertEquals(1, matroska.size)
        assertTrue(readerOf(matroska.single()) is ResyncingEbmlReader)
    }

    private fun readerOf(extractor: MatroskaExtractor): Any? =
        MatroskaExtractor::class.java.getDeclaredField("reader").apply { isAccessible = true }.get(extractor)

    /** Returns the sample times the extractor outputs. */
    private fun extract(extractor: Extractor, data: ByteArray): List<Long> {
        val output = RecordingOutput()
        extractor.init(output)
        val input = DefaultExtractorInput(ByteReader(data), 0, data.size.toLong())
        val positionHolder = PositionHolder()
        while (true) {
            val result = extractor.read(input, positionHolder)
            if (result == Extractor.RESULT_END_OF_INPUT) break
            check(result == Extractor.RESULT_CONTINUE) { "unexpected result $result" }
        }
        return output.sampleTimesUs
    }

    private fun intactFile(): ByteArray = file(damaged = false)

    private fun damagedFile(): ByteArray = file(damaged = true)

    /** EBML header, Segment, Info, one AC-3 track, then two Clusters of one block each. */
    private fun file(damaged: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        fun put(vararg bytes: Int) = bytes.forEach(out::write)
        fun putAscii(text: String) = text.forEach { out.write(it.code) }
        // EBML header: DocType "webm".
        put(0x1A, 0x45, 0xDF, 0xA3, 0x87, 0x42, 0x82, 0x84)
        putAscii("webm")
        // Segment of unknown size.
        put(0x18, 0x53, 0x80, 0x67, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
        // Info: TimecodeScale 1 ms.
        put(0x15, 0x49, 0xA9, 0x66, 0x87, 0x2A, 0xD7, 0xB1, 0x83, 0x0F, 0x42, 0x40)
        // Tracks > TrackEntry: number 1, uid 1, audio, A_AC3.
        put(0x16, 0x54, 0xAE, 0x6B, 0x93, 0xAE, 0x91)
        put(0xD7, 0x81, 0x01, 0x73, 0xC5, 0x81, 0x01, 0x83, 0x81, 0x02, 0x86, 0x85)
        putAscii("A_AC3")
        // Cluster 1 (its size runs past the damage): timecode 0, one keyframe block.
        put(0x1F, 0x43, 0xB6, 0x75, 0x40, 0x7F)
        put(0xE7, 0x81, 0x00)
        put(0xA3, 0x88, 0x81, 0x00, 0x00, 0x80, 0x0B, 0x77, 0x01, 0x02)
        if (damaged) {
            // Void element whose 8-byte size 0x97D129C4 wraps to -1747899964 as an int, then noise.
            put(0xEC, 0x01, 0x00, 0x00, 0x00, 0x97, 0xD1, 0x29, 0xC4)
            repeat(40) { put((it * 37 + 11) and 0x7F) }
        } else {
            // Pad Cluster 1 to its declared 127 bytes (13 used) with a 3-byte header + 111-byte Void.
            put(0xEC, 0x40, 0x6F)
            repeat(0x6F) { put(0) }
        }
        // Cluster 2: timecode 9, one keyframe block.
        put(0x1F, 0x43, 0xB6, 0x75, 0x8D)
        put(0xE7, 0x81, 0x09)
        put(0xA3, 0x88, 0x81, 0x00, 0x00, 0x80, 0x0B, 0x77, 0x03, 0x04)
        return out.toByteArray()
    }

    private class ByteReader(private val data: ByteArray) : DataReader {
        private var position = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, data.size - position)
            System.arraycopy(data, position, buffer, offset, count)
            position += count
            return count
        }
    }

    private class RecordingOutput : ExtractorOutput {
        val sampleTimesUs = mutableListOf<Long>()

        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            override fun format(format: Format) = Unit

            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                val scratch = ByteArray(length)
                return input.read(scratch, 0, length)
            }

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                data.skipBytes(length)
            }

            override fun sampleMetadata(
                timeUs: Long,
                flags: Int,
                size: Int,
                offset: Int,
                cryptoData: TrackOutput.CryptoData?,
            ) {
                sampleTimesUs += timeUs
            }
        }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit
    }
}
