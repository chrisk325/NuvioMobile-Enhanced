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
 * DefaultExtractorInput, which ends playback. This reader instead closes the elements opened below
 * the Segment and searches for the next level 1 element (normally the next Cluster), so playback
 * carries on from there. The search also returns end of input instead of throwing when the stream
 * ends before a level 1 element is found.
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
                var result = varintReader.readUnsignedVarint(input, true, false, MAX_ID_BYTES)
                if (result == C.RESULT_MAX_LENGTH_EXCEEDED.toLong()) {
                    result = maybeResyncToNextLevel1Element(input, processor)
                }
                if (result == C.RESULT_END_OF_INPUT.toLong()) return false
                // Element IDs are at most 4 bytes, so they fit in an int.
                elementId = result.toInt()
                elementState = ELEMENT_STATE_READ_CONTENT_SIZE
            }

            if (elementState == ELEMENT_STATE_READ_CONTENT_SIZE) {
                elementContentSize = varintReader.readUnsignedVarint(input, false, true, MAX_LENGTH_BYTES)
                elementState = ELEMENT_STATE_READ_CONTENT
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
                        startResync(input, processor)
                    } else {
                        processor.binaryElement(elementId, elementContentSize.toInt(), input)
                        elementState = ELEMENT_STATE_READ_ID
                        return true
                    }
                }
                EbmlProcessor.ELEMENT_TYPE_UNKNOWN -> {
                    if (elementContentSize > Int.MAX_VALUE) {
                        startResync(input, processor)
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
     * Handles a binary or unknown element whose size does not fit in an int: no real Matroska
     * element of these types is that large, so the reader has lost its place.
     */
    private fun startResync(input: ExtractorInput, processor: EbmlProcessor) {
        Log.w(
            TAG,
            "Element 0x${Integer.toHexString(elementId)} declares $elementContentSize bytes at position " +
                "${input.position}; resyncing to the next level 1 element",
        )
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
