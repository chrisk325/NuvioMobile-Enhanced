package com.nuvio.app.features.servers

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerFormattedTitleTest {
    @Test
    fun splitsFormatterBlockIntoNameAndDescription() {
        val title = "Remux T1 : 6250\n🌳🌷 › 4K\n🦁🐳 › nzbs.in\r\n🍒🔮 › 95.78 GB\n"
        assertEquals(
            "Remux T1 : 6250" to "🌳🌷 › 4K\n🦁🐳 › nzbs.in\n🍒🔮 › 95.78 GB",
            ServerStreams.splitFormattedTitle(title, null),
        )
    }

    @Test
    fun keepsSingleLineTitleAndItsDescription() {
        assertEquals("2160p" to "HEVC • MKV", ServerStreams.splitFormattedTitle("2160p", "HEVC • MKV"))
        assertEquals("4K Remux" to null, ServerStreams.splitFormattedTitle("\n4K Remux\n", null))
    }
}
