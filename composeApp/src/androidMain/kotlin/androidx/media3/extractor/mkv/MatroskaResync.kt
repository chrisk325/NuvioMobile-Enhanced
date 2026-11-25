package androidx.media3.extractor.mkv

import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Field

/**
 * Puts a [ResyncingEbmlReader] into a [MatroskaExtractor] in place of Media3's DefaultEbmlReader.
 *
 * Every public MatroskaExtractor constructor builds its own DefaultEbmlReader, and subclasses the
 * app does not own (libass's AssMatroskaExtractor) go through them too, so the reader is replaced
 * after construction. The new reader is given the extractor's own processor, which the old reader
 * was initialised with. If Media3 changes these fields the extractor is left as it is: playback
 * works as before, without the resync.
 */
@UnstableApi
internal object MatroskaResync {
    private const val TAG = "MatroskaResync"
    private const val DEFAULT_READER = "androidx.media3.extractor.mkv.DefaultEbmlReader"

    private val readerField: Field? = declaredField(MatroskaExtractor::class.java, "reader")
    private val processorField: Field? = runCatching { Class.forName(DEFAULT_READER) }
        .getOrNull()
        ?.let { declaredField(it, "processor") }

    @Volatile
    private var warned = false

    /** Returns true when [extractor] reads with a [ResyncingEbmlReader] after the call. */
    fun install(extractor: MatroskaExtractor): Boolean {
        val readerField = readerField
        val processorField = processorField
        if (readerField == null || processorField == null) return unavailable("fields not found")
        return try {
            val current = readerField.get(extractor)
            if (current is ResyncingEbmlReader) return true
            if (current == null || current.javaClass.name != DEFAULT_READER) {
                return unavailable("unexpected reader ${current?.javaClass?.name}")
            }
            val processor = processorField.get(current) as? EbmlProcessor
                ?: return unavailable("reader has no processor")
            val replacement = ResyncingEbmlReader()
            replacement.init(processor)
            readerField.set(extractor, replacement)
            true
        } catch (e: ReflectiveOperationException) {
            unavailable(e.toString())
        } catch (e: RuntimeException) {
            unavailable(e.toString())
        }
    }

    private fun unavailable(reason: String): Boolean {
        if (!warned) {
            warned = true
            Log.w(TAG, "Matroska resync not installed: $reason")
        }
        return false
    }

    private fun declaredField(owner: Class<*>, name: String): Field? =
        runCatching { owner.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
}
