package com.nuvio.app.features.player.anime

import com.nuvio.tv.libass.media.AssHandler
import com.nuvio.tv.libass.media.effect.AssFrameTimeMapper
import kotlin.test.Test
import kotlin.test.assertEquals

class LibassEffectsTimingTest {
    @Test
    fun mapsEffectsPipelineTimeBackToMediaTimeAfterResume() {
        val rendererOffsetUs = 1_000_000_000_000L
        val resumePositionUs = 600_000_000L
        val mapper = AssFrameTimeMapper()
        mapper.onEnabled(rendererOffsetUs + resumePositionUs, rendererOffsetUs)
        assertEquals(resumePositionUs + 5_000_000L, mapper.toMediaTimeUs(5_000_000L))
    }

    @Test
    fun frameTimeMapperGetterKeptByProguardExists() {
        val getter = AssHandler::class.java.getMethod("getFrameTimeMapper\$nuvio_libass_release")
        assertEquals(AssFrameTimeMapper::class.java, getter.returnType)
    }
}
