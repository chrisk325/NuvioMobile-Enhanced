package com.nuvio.app.features.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mkv.MatroskaResync
import androidx.media3.extractor.text.SubtitleParser

/**
 * Gives every Matroska extractor this factory makes a reader that resyncs to the next Cluster on
 * damaged data instead of ending playback (see [MatroskaResync]). Apply it after any wrapper that
 * replaces the Matroska extractor, since a replacement starts with Media3's own reader again.
 */
@OptIn(UnstableApi::class)
internal fun ExtractorsFactory.withMatroskaResync(): ExtractorsFactory = MatroskaResyncExtractorsFactory(this)

@OptIn(UnstableApi::class)
private class MatroskaResyncExtractorsFactory(private val delegate: ExtractorsFactory) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = delegate.createExtractors().withResync()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).withResync()

    // DefaultMediaSourceFactory configures subtitle extraction through these; they must reach the delegate.
    @Deprecated("Deprecated in Media3")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        @Suppress("DEPRECATION")
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies: Int,
    ): ExtractorsFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }

    private fun Array<Extractor>.withResync(): Array<Extractor> {
        forEach { extractor ->
            (extractor.underlyingImplementation as? MatroskaExtractor)?.let(MatroskaResync::install)
        }
        return this
    }
}
