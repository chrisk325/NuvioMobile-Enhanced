package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveTvXmltvParserTest {

    private val windowStart = 0L
    private val windowEnd = Long.MAX_VALUE

    private val xml = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
        for (channel in 0 until 40) {
            append("<channel id=\"ch$channel.uk\"><display-name>Chännel $channel HD</display-name></channel>\n")
        }
        for (channel in 0 until 40) {
            for (hour in 0 until 24) {
                val start = 20250101000000L + hour * 10000L
                append("<programme start=\"$start +0000\" channel=\"ch$channel.uk\">")
                append("<title>Shöw &amp; $hour — 日本 🎬</title><desc><![CDATA[Ünïcødé <b>$channel</b>]]></desc>")
                append("</programme>\n")
            }
        }
        append("</tv>\n")
    }

    @Test
    fun guideFedInSmallPartsMatchesGuideReadWhole() {
        val whole = parseXmltvGuide(xml, windowStart, windowEnd)
        val bytes = xml.encodeToByteArray()
        for (partSize in listOf(1, 2, 3, 5, 7, 64, 4096)) {
            val parser = XmltvGuideParser(windowStart, windowEnd)
            var index = 0
            while (index < bytes.size) {
                val end = minOf(index + partSize, bytes.size)
                parser.feed(bytes.copyOfRange(index, end), end - index)
                index = end
            }
            assertEquals(whole, parser.finish(), "parts of $partSize bytes")
        }
        assertEquals(40, whole.programsByEpgKey.size)
        assertEquals("Shöw & 0 — 日本 🎬", whole.programsByEpgKey.getValue("ch0.uk").first().title)
        assertEquals("ch3.uk", whole.epgKeysByName["chännel3"])
    }
}
