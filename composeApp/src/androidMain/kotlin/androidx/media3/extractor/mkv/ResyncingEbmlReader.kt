// Lives in Media3's package because EbmlReader, VarintReader and the reader field of
// MatroskaExtractor are package-private there; the class itself is Nuvio's.
package androidx.media3.extractor.mkv

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorInput
import java.io.EOFException
import java.util.ArrayDeque

/**
 * Media3's DefaultEbmlReader (1.8.0), plus a resync for element sizes that cannot be valid.
 *
 * Media3 casts the size of a binary or unknown element to an int. On damaged data (in the file or
 * the stream) the header it just read is noise, the size wraps negative, and skipping a negative
 * length throws "ArrayIndexOutOfBoundsException: src.length=65536 srcPos=-1747899964 ..." inside
 * DefaultExtractorInput, which ends playback. Damaged data can also put a zero byte where an
 * element id or size starts; that byte has no length marker and Media3's VarintReader throws
 * "IllegalStateException: No valid varint length mask found", which ends playback too. In both
 * cases this reader instead closes the elements opened below the Segment and searches for the next
 * level 1 element (normally the next Cluster), so playback carries on from there. The search also
 * returns end of input instead of throwing when the stream ends before a level 1 element is found.
 * A Block or SimpleBlock whose header is damaged (its track number starts with a zero byte, or its
 * lacing sizes are invalid) is skipped, since MatroskaExtractor would fail on it too.
 *
 * Ported from NuvioTV's dvmkv DefaultEbmlReader. [MatroskaResync] swaps it into extractors.
 */
@UnstableApi
internal class ResyncingEbmlReader : EbmlReader {
    private val scratch = ByteArray(8)
    private val masterElementsStack = ArrayDeque<MasterElement>()
    private val varintReader = VarintReader()

    private var processor: EbmlProcessor? = null
    private var elementState = ELEMENT_STATE_READ_ID
    private var elementId = 0
    private var elementContentSize = 0L

    // True once the content of the current binary element has been handed to the processor. A read
    // error inside the processor hands the same element over again to resume it; by then its first
    // byte has been read, so it must not be checked again.
    private var binaryContentStarted = false

    // Bytes of the current block peeked by findBlockDamage.
    private var blockBytesPeeked = 0

    override fun init(processor: EbmlProcessor) {
        this.processor = processor
    }

    override fun reset() {
        elementState = ELEMENT_STATE_READ_ID
        masterElementsStack.clear()
        varintReader.reset()
    }

    override fun read(input: ExtractorInput): Boolean {
        val processor = checkNotNull(processor)
        while (true) {
            val head = masterElementsStack.peek()
            if (head != null && input.position >= head.elementEndPosition) {
                processor.endMasterElement(masterElementsStack.pop().elementId)
                return true
            }

            if (elementState == ELEMENT_STATE_RESYNC) {
                val result = maybeResyncToNextLevel1Element(input, processor)
                if (result == C.RESULT_END_OF_INPUT.toLong()) return false
                elementId = result.toInt()
                elementState = ELEMENT_STATE_READ_CONTENT_SIZE
            }

            if (elementState == ELEMENT_STATE_READ_ID) {
                var result = readVarintOrNull(input, allowEndOfInput = true, removeLengthMask = false, MAX_ID_BYTES)
                if (result == null) {
                    startResync(input, processor, "Invalid element id")
                    continue
                }
                if (result == C.RESULT_MAX_LENGTH_EXCEEDED.toLong()) {
                    result = maybeResyncToNextLevel1Element(input, processor)
                }
                if (result == C.RESULT_END_OF_INPUT.toLong()) return false
                // Element IDs are at most 4 bytes, so they fit in an int.
                elementId = result.toInt()
                elementState = ELEMENT_STATE_READ_CONTENT_SIZE
            }

            if (elementState == ELEMENT_STATE_READ_CONTENT_SIZE) {
                val size = readVarintOrNull(input, allowEndOfInput = false, removeLengthMask = true, MAX_LENGTH_BYTES)
                if (size == null) {
                    startResync(input, processor, "Element 0x${Integer.toHexString(elementId)} has an invalid size")
                    continue
                }
                elementContentSize = size
                elementState = ELEMENT_STATE_READ_CONTENT
                binaryContentStarted = false
            }

            when (val type = processor.getElementType(elementId)) {
                EbmlProcessor.ELEMENT_TYPE_MASTER -> {
                    val elementContentPosition = input.position
                    val elementEndPosition = elementContentPosition + elementContentSize
                    masterElementsStack.push(MasterElement(elementId, elementEndPosition))
                    processor.startMasterElement(elementId, elementContentPosition, elementContentSize)
                    elementState = ELEMENT_STATE_READ_ID
                    return true
                }
                EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT -> {
                    if (elementContentSize > MAX_INTEGER_ELEMENT_SIZE_BYTES) {
                        throw ParserException.createForMalformedContainer(
                            "Invalid integer size: $elementContentSize",
                            /* cause= */ null,
                        )
                    }
                    processor.integerElement(elementId, readInteger(input, elementContentSize.toInt()))
                    elementState = ELEMENT_STATE_READ_ID
                    return true
                }
                EbmlProcessor.ELEMENT_TYPE_FLOAT -> {
                    if (elementContentSize != VALID_FLOAT32_ELEMENT_SIZE_BYTES.toLong() &&
                        elementContentSize != VALID_FLOAT64_ELEMENT_SIZE_BYTES.toLong()
                    ) {
                        throw ParserException.createForMalformedContainer(
                            "Invalid float size: $elementContentSize",
                            /* cause= */ null,
                        )
                    }
                    processor.floatElement(elementId, readFloat(input, elementContentSize.toInt()))
                    elementState = ELEMENT_STATE_READ_ID
                    return true
                }
                EbmlProcessor.ELEMENT_TYPE_STRING -> {
                    if (elementContentSize > Int.MAX_VALUE) {
                        throw ParserException.createForMalformedContainer(
                            "String element size: $elementContentSize",
                            /* cause= */ null,
                        )
                    }
                    processor.stringElement(elementId, readString(input, elementContentSize.toInt()))
                    elementState = ELEMENT_STATE_READ_ID
                    return true
                }
                EbmlProcessor.ELEMENT_TYPE_BINARY -> {
                    if (elementContentSize > Int.MAX_VALUE) {
                        startResync(input, processor, oversizeReason())
                    } else {
                        val blockDamage = if (binaryContentStarted) null else findBlockDamage(input)
                        if (blockDamage != null) {
                            Log.w(TAG, "Block at position ${input.position} has $blockDamage; skipping it")
                            input.skipFully(elementContentSize.toInt())
                        } else {
                            binaryContentStarted = true
                            processor.binaryElement(elementId, elementContentSize.toInt(), input)
                        }
                        elementState = ELEMENT_STATE_READ_ID
                        return true
                    }
                }
                EbmlProcessor.ELEMENT_TYPE_UNKNOWN -> {
                    if (elementContentSize > Int.MAX_VALUE) {
                        startResync(input, processor, oversizeReason())
                    } else {
                        input.skipFully(elementContentSize.toInt())
                        elementState = ELEMENT_STATE_READ_ID
                        elementContentSize = 0
                        elementId = 0
                    }
                }
                else -> throw ParserException.createForMalformedContainer(
                    "Invalid element type $type",
                    /* cause= */ null,
                )
            }
        }
    }

    /**
     * Reads a varint, or returns null when its first byte is zero: no varint starts with a zero byte,
     * so the reader has lost its place. Media3's VarintReader throws IllegalStateException for that
     * byte after consuming it and before changing its own state, so it is ready for the next varint.
     */
    private fun readVarintOrNull(
        input: ExtractorInput,
        allowEndOfInput: Boolean,
        removeLengthMask: Boolean,
        maximumAllowedLength: Int,
    ): Long? = try {
        varintReader.readUnsignedVarint(input, allowEndOfInput, removeLengthMask, maximumAllowedLength)
    } catch (_: IllegalStateException) {
        null
    }

    /**
     * Checks the header of the current element if it is a Block or SimpleBlock, and returns what is
     * damaged in it, or null when it is intact (or the element is not a block).
     *
     * A block starts with its track number as a varint, a timecode, flags and, for a laced block,
     * the sizes of its frames. Damaged data there makes MatroskaExtractor fail ("IllegalStateException:
     * No valid varint length mask found", "EBML lacing sample size out of range") or produce frame
     * sizes below zero or beyond the block, which ends playback. The element's own size was read
     * fine, so only this block is lost when the reader skips it. The header is checked as
     * MatroskaExtractor (1.8.0) parses it. It is only peeked, so a read error here leaves the input
     * where the block starts.
     */
    private fun findBlockDamage(input: ExtractorInput): String? {
        if ((elementId != ID_BLOCK && elementId != ID_SIMPLE_BLOCK) || elementContentSize == 0L) return null
        blockBytesPeeked = 0
        return try {
            peekBlockDamage(input, elementContentSize.toInt())
        } finally {
            input.resetPeekPosition()
        }
    }

    private fun peekBlockDamage(input: ExtractorInput, blockSize: Int): String? {
        val firstByte = peekBlockByte(input, blockSize)
        if (firstByte == 0) return "an invalid track number"
        val trackNumberLength = VarintReader.parseUnsignedVarintLength(firstByte)
        // The rest of the track number, the timecode (2 bytes) and the flags (1 byte).
        var flags = 0
        for (i in 1 until trackNumberLength + 3) {
            flags = peekBlockByte(input, blockSize)
            if (flags == PAST_BLOCK_END) return HEADER_TOO_LONG
        }
        val lacing = (flags and 0x06) shr 1
        if (lacing == LACING_NONE) return null
        val frameCountByte = peekBlockByte(input, blockSize)
        if (frameCountByte == PAST_BLOCK_END) return HEADER_TOO_LONG
        val frameCount = frameCountByte + 1
        if (lacing == LACING_FIXED_SIZE) return null
        // Sizes of all frames but the last, which takes what is left of the block.
        var totalFramesSize = 0L
        var frameSize = 0L
        for (frame in 0 until frameCount - 1) {
            if (lacing == LACING_XIPH) {
                frameSize = 0
                do {
                    val sizeByte = peekBlockByte(input, blockSize)
                    if (sizeByte == PAST_BLOCK_END) return HEADER_TOO_LONG
                    frameSize += sizeByte
                } while (sizeByte == 0xFF)
            } else {
                // LACING_EBML: the first size is a varint, later ones are signed varint offsets.
                val sizeFirstByte = peekBlockByte(input, blockSize)
                if (sizeFirstByte == PAST_BLOCK_END) return HEADER_TOO_LONG
                if (sizeFirstByte == 0) return "an invalid lacing size"
                val sizeLength = VarintReader.parseUnsignedVarintLength(sizeFirstByte)
                var value = (sizeFirstByte and (0x80 shr (sizeLength - 1)).inv()).toLong()
                for (i in 1 until sizeLength) {
                    val sizeByte = peekBlockByte(input, blockSize)
                    if (sizeByte == PAST_BLOCK_END) return HEADER_TOO_LONG
                    value = (value shl 8) or sizeByte.toLong()
                }
                if (frame > 0) value -= (1L shl (6 + (sizeLength - 1) * 7)) - 1
                if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) return "a lacing size out of range"
                frameSize = if (frame == 0) value else frameSize + value
            }
            if (frameSize < 0) return "a negative lacing size"
            totalFramesSize += frameSize
            if (totalFramesSize > blockSize - blockBytesPeeked) return "lacing sizes larger than the block"
        }
        return null
    }

    /** Peeks the next byte of the current block, or returns [PAST_BLOCK_END] when the block has no more bytes. */
    private fun peekBlockByte(input: ExtractorInput, blockSize: Int): Int {
        if (blockBytesPeeked >= blockSize) return PAST_BLOCK_END
        input.peekFully(scratch, 0, 1)
        blockBytesPeeked++
        return scratch[0].toInt() and 0xFF
    }

    /**
     * A binary or unknown element whose size does not fit in an int: no real Matroska element of
     * these types is that large, so the reader has lost its place.
     */
    private fun oversizeReason() =
        "Element 0x${Integer.toHexString(elementId)} declares $elementContentSize bytes"

    /** Gives up on the current element after damaged data and starts the search for a level 1 element. */
    private fun startResync(input: ExtractorInput, processor: EbmlProcessor, reason: String) {
        Log.w(TAG, "$reason at position ${input.position}; resyncing to the next level 1 element")
        elementState = ELEMENT_STATE_RESYNC
        elementContentSize = 0
        elementId = 0
        // A level 1 element found by the search is a child of the Segment, so whatever was open below
        // the Segment has ended. Closing those elements commits a block that was already read in full
        // and resets the block state; leaving them open would apply the old block's state to the
        // first block found after the search.
        while (true) {
            val head = masterElementsStack.peek() ?: break
            if (head.elementId == ID_SEGMENT) break
            masterElementsStack.pop()
            processor.endMasterElement(head.elementId)
        }
    }

    /**
     * Searches byte by byte for the next level 1 element and returns its id, or
     * [C.RESULT_END_OF_INPUT] when the input ends first.
     */
    private fun maybeResyncToNextLevel1Element(input: ExtractorInput, processor: EbmlProcessor): Long {
        input.resetPeekPosition()
        while (true) {
            if (!peekFullyOrEnd(input, scratch, MAX_ID_BYTES)) return C.RESULT_END_OF_INPUT.toLong()
            val varintLength = VarintReader.parseUnsignedVarintLength(scratch[0].toInt())
            if (varintLength != C.LENGTH_UNSET && varintLength <= MAX_ID_BYTES) {
                val potentialId = VarintReader.assembleVarint(scratch, varintLength, false).toInt()
                if (processor.isLevel1Element(potentialId)) {
                    input.skipFully(varintLength)
                    return potentialId.toLong()
                }
            }
            try {
                input.skipFully(1)
            } catch (_: EOFException) {
                return C.RESULT_END_OF_INPUT.toLong()
            }
        }
    }

    private fun readInteger(input: ExtractorInput, byteLength: Int): Long {
        input.readFully(scratch, 0, byteLength)
        var value = 0L
        for (i in 0 until byteLength) {
            value = (value shl 8) or (scratch[i].toLong() and 0xFF)
        }
        return value
    }

    private fun readFloat(input: ExtractorInput, byteLength: Int): Double {
        val integerValue = readInteger(input, byteLength)
        return if (byteLength == VALID_FLOAT32_ELEMENT_SIZE_BYTES) {
            java.lang.Float.intBitsToFloat(integerValue.toInt()).toDouble()
        } else {
            java.lang.Double.longBitsToDouble(integerValue)
        }
    }

    private class MasterElement(val elementId: Int, val elementEndPosition: Long)

    private companion object {
        const val TAG = "ResyncingEbmlReader"

        const val ELEMENT_STATE_READ_ID = 0
        const val ELEMENT_STATE_READ_CONTENT_SIZE = 1
        const val ELEMENT_STATE_READ_CONTENT = 2
        // Searching byte by byte for the next level 1 element after an element whose size cannot be
        // valid. Kept as a state so a read error during the search resumes the search, not ID parsing.
        const val ELEMENT_STATE_RESYNC = 3

        /** Matroska Segment, the only master element kept on the stack across a resync. */
        const val ID_SEGMENT = 0x18538067

        /** Matroska Block and SimpleBlock, whose content starts with a header MatroskaExtractor parses. */
        const val ID_BLOCK = 0xA1
        const val ID_SIMPLE_BLOCK = 0xA3

        const val LACING_NONE = 0
        const val LACING_XIPH = 1
        const val LACING_FIXED_SIZE = 2
        const val LACING_EBML = 3

        /** Returned by [peekBlockByte] when the block header runs past the end of the block. */
        const val PAST_BLOCK_END = -1
        const val HEADER_TOO_LONG = "a header longer than the block"

        const val MAX_ID_BYTES = 4
        const val MAX_LENGTH_BYTES = 8

        const val MAX_INTEGER_ELEMENT_SIZE_BYTES = 8
        const val VALID_FLOAT32_ELEMENT_SIZE_BYTES = 4
        const val VALID_FLOAT64_ELEMENT_SIZE_BYTES = 8

        fun peekFullyOrEnd(input: ExtractorInput, target: ByteArray, length: Int): Boolean {
            val streamLength = input.length
            if (streamLength != C.LENGTH_UNSET.toLong() && streamLength - input.peekPosition < length) return false
            return try {
                input.peekFully(target, 0, length, /* allowEndOfInput= */ true)
            } catch (_: EOFException) {
                false
            }
        }

        /** Reads a string, dropping its zero padding. */
        fun readString(input: ExtractorInput, byteLength: Int): String {
            if (byteLength == 0) return ""
            val stringBytes = ByteArray(byteLength)
            input.readFully(stringBytes, 0, byteLength)
            var trimmedLength = byteLength
            while (trimmedLength > 0 && stringBytes[trimmedLength - 1].toInt() == 0) trimmedLength--
            return String(stringBytes, 0, trimmedLength)
        }
    }
}
