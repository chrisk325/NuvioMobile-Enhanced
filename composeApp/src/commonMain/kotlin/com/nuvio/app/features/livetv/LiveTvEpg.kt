package com.nuvio.app.features.livetv

internal const val LIVE_TV_EPG_PAST_WINDOW_MILLIS = 3L * 60L * 60L * 1000L
internal const val LIVE_TV_EPG_FUTURE_WINDOW_MILLIS = 48L * 60L * 60L * 1000L
private const val LIVE_TV_EPG_DEFAULT_DURATION_MILLIS = 60L * 60L * 1000L

/**
 * The most guide XML read from one source. The guide is parsed as it arrives and only the
 * programmes in the window are kept, so this only stops a source that never ends.
 */
internal const val LIVE_TV_EPG_MAX_XML_BYTES = 1L shl 30

/** Guide text gathered before it is parsed, so a parse pass is not run for every network read. */
private const val XMLTV_PARSE_BATCH_CHARS = 512 * 1024

private val epgRequestHeaders = mapOf(
    "Accept" to "application/xml, text/xml, application/gzip, application/octet-stream, */*",
    "User-Agent" to "Nuvio/1.0",
)

internal data class LiveTvEpgGuide(
    val programsByEpgKey: Map<String, List<LiveTvEpgProgram>> = emptyMap(),
    val epgKeysByName: Map<String, String> = emptyMap(),
    val programsByChannelId: Map<String, List<LiveTvEpgProgram>> = emptyMap(),
)

/**
 * Downloads an XMLTV guide and parses it while it downloads. Xtream panels' `xmltv.php` holds
 * every channel of the panel for a week or more, often hundreds of megabytes, so it is never held
 * whole: each part is parsed as it arrives and only the programmes in the window are kept.
 */
internal suspend fun fetchXmltvGuide(url: String, nowMillis: Long): LiveTvEpgGuide {
    val parser = XmltvGuideParser(
        windowStartMillis = nowMillis - LIVE_TV_EPG_PAST_WINDOW_MILLIS,
        windowEndMillis = nowMillis + LIVE_TV_EPG_FUTURE_WINDOW_MILLIS,
    )
    liveTvStreamGuide(url, epgRequestHeaders, LIVE_TV_EPG_MAX_XML_BYTES) { bytes, length ->
        parser.feed(bytes, length)
    }
    return parser.finish()
}

internal fun parseXmltvGuide(
    xml: String,
    windowStartMillis: Long,
    windowEndMillis: Long,
): LiveTvEpgGuide = XmltvGuideParser(windowStartMillis, windowEndMillis).apply { feedText(xml) }.finish()

/**
 * Parses XMLTV fed in parts. Text is held until a `<channel` or `<programme` start, and everything
 * before that start is complete elements, as neither is nested in the other.
 */
internal class XmltvGuideParser(
    private val windowStartMillis: Long,
    private val windowEndMillis: Long,
) {
    private val keysByName = HashMap<String, String>()
    private val idKeysByName = HashMap<String, String>()
    private val programs = HashMap<String, MutableList<LiveTvEpgProgram>>()
    private val pending = StringBuilder()
    private var utf8Carry = ByteArray(0)

    fun feed(bytes: ByteArray, length: Int) {
        if (length <= 0) return
        val data: ByteArray
        val size: Int
        if (utf8Carry.isEmpty()) {
            data = bytes
            size = length
        } else {
            data = utf8Carry + bytes.copyOf(length)
            size = data.size
        }
        val complete = completeUtf8Length(data, size)
        utf8Carry = if (complete < size) data.copyOfRange(complete, size) else ByteArray(0)
        if (complete > 0) feedText(data.decodeToString(0, complete))
    }

    fun feedText(text: String) {
        if (text.isEmpty()) return
        pending.append(text)
        if (pending.length < XMLTV_PARSE_BATCH_CHARS) return
        val split = lastElementStart(pending)
        if (split <= 0) return
        val segment = pending.substring(0, split)
        pending.deleteRange(0, split)
        parseSegment(segment)
    }

    fun finish(): LiveTvEpgGuide {
        if (utf8Carry.isNotEmpty()) {
            pending.append(utf8Carry.decodeToString())
            utf8Carry = ByteArray(0)
        }
        if (pending.isNotEmpty()) {
            val rest = pending.toString()
            pending.clear()
            parseSegment(rest)
        }
        val finalized = programs
            .mapValues { (_, list) -> finalizeEpgPrograms(list, windowStartMillis) }
            .filterValues { it.isNotEmpty() }
        val names = HashMap(keysByName)
        idKeysByName.forEach { (name, key) -> names.getOrPut(name) { key } }
        return LiveTvEpgGuide(
            programsByEpgKey = finalized,
            epgKeysByName = names.filterValues { it in finalized },
        )
    }

    private fun parseSegment(xml: String) {
        parseChannels(xml)
        parsePrograms(xml)
    }

    private fun parseChannels(xml: String) {
        var index = 0
        while (true) {
            val start = xml.indexOf("<channel", index)
            if (start < 0) break
            if (!isXmlTagBoundary(xml.getOrNull(start + 8))) {
                index = start + 8
                continue
            }
            val tagEnd = xml.indexOf('>', start)
            if (tagEnd < 0) break
            val openTag = xml.substring(start, tagEnd)
            val selfClosing = openTag.endsWith('/')
            val close = if (selfClosing) tagEnd else xml.indexOf("</channel>", tagEnd).takeIf { it >= 0 } ?: break
            index = close + 1
            val id = readXmlAttribute(openTag, "id")?.let(::decodeXmlEntities)?.trim()?.takeIf(String::isNotBlank) ?: continue
            val key = liveTvEpgKey(id)
            if (!selfClosing) {
                readXmlElementTexts(xml.substring(tagEnd + 1, close), "display-name").forEach { name ->
                    normalizeEpgChannelName(name).takeIf(String::isNotBlank)?.let { keysByName.getOrPut(it) { key } }
                }
            }
            epgIdNameCandidates(id).forEach { candidate -> idKeysByName.getOrPut(candidate) { key } }
        }
    }

    private fun parsePrograms(xml: String) {
        var index = 0
        while (true) {
            val start = xml.indexOf("<programme", index)
            if (start < 0) break
            if (!isXmlTagBoundary(xml.getOrNull(start + 10))) {
                index = start + 10
                continue
            }
            val tagEnd = xml.indexOf('>', start)
            if (tagEnd < 0) break
            val openTag = xml.substring(start, tagEnd)
            val selfClosing = openTag.endsWith('/')
            val close = if (selfClosing) tagEnd else xml.indexOf("</programme>", tagEnd).takeIf { it >= 0 } ?: break
            index = close + 1
            if (selfClosing) continue
            val startMillis = readXmlAttribute(openTag, "start")?.let(::parseXmltvTime) ?: continue
            val stopMillis = readXmlAttribute(openTag, "stop")?.let(::parseXmltvTime)
            if (startMillis > windowEndMillis) continue
            if (stopMillis != null && stopMillis < windowStartMillis) continue
            val channel = readXmlAttribute(openTag, "channel")?.let(::decodeXmlEntities)?.trim()?.takeIf(String::isNotBlank) ?: continue
            val body = xml.substring(tagEnd + 1, close)
            val title = readXmlElementTexts(body, "title", limit = 1).firstOrNull() ?: continue
            programs.getOrPut(liveTvEpgKey(channel)) { mutableListOf() } += LiveTvEpgProgram(
                title = title,
                startMillis = startMillis,
                endMillis = stopMillis ?: -1L,
                description = readXmlElementTexts(body, "desc", limit = 1).firstOrNull(),
                category = readXmlElementTexts(body, "category", limit = 1).firstOrNull(),
            )
        }
    }
}

/** Where the last `<channel` or `<programme` start in [text] is, or -1. */
private fun lastElementStart(text: StringBuilder): Int {
    var index = text.length - 1
    while (index >= 0) {
        if (text[index] == '<' && (text.hasAt(index, "<programme") || text.hasAt(index, "<channel"))) return index
        index--
    }
    return -1
}

private fun StringBuilder.hasAt(index: Int, value: String): Boolean {
    if (index + value.length > length) return false
    for (offset in value.indices) {
        if (this[index + offset] != value[offset]) return false
    }
    return true
}

/** How many of the first [length] bytes of [data] end on a whole UTF-8 character. */
private fun completeUtf8Length(data: ByteArray, length: Int): Int {
    var index = length - 1
    while (index >= 0 && length - index <= 4) {
        val byte = data[index].toInt() and 0xFF
        if (byte and 0xC0 != 0x80) {
            val needed = when {
                byte >= 0xF0 -> 4
                byte >= 0xE0 -> 3
                byte >= 0xC0 -> 2
                else -> 1
            }
            return if (length - index >= needed) length else index
        }
        index--
    }
    return length
}

internal fun finalizeEpgPrograms(
    programs: List<LiveTvEpgProgram>,
    windowStartMillis: Long,
): List<LiveTvEpgProgram> {
    val sorted = programs.sortedBy { it.startMillis }.distinctBy { it.startMillis }
    return sorted.mapIndexed { index, program ->
        if (program.endMillis > program.startMillis) {
            program
        } else {
            val nextStart = sorted.getOrNull(index + 1)?.startMillis
            program.copy(endMillis = nextStart ?: (program.startMillis + LIVE_TV_EPG_DEFAULT_DURATION_MILLIS))
        }
    }.filter { it.endMillis >= windowStartMillis }
}

internal fun matchEpgToChannels(
    channels: List<LiveTvChannel>,
    guides: List<LiveTvEpgGuide>,
): Map<String, List<LiveTvEpgProgram>> {
    if (guides.isEmpty() || channels.isEmpty()) return emptyMap()
    val result = HashMap<String, List<LiveTvEpgProgram>>()
    channels.forEach { channel ->
        val epgKey = channel.epgId?.trim()?.takeIf(String::isNotBlank)?.let(::liveTvEpgKey)
        val nameKey = normalizeEpgChannelName(channel.name)
        val programs = guides.firstNotNullOfOrNull { guide ->
            guide.programsByChannelId[channel.id]?.takeIf { it.isNotEmpty() }
        } ?: epgKey?.let { key ->
            guides.firstNotNullOfOrNull { guide -> guide.programsByEpgKey[key] }
        } ?: epgKey?.let { key ->
            epgIdNameCandidates(key).firstNotNullOfOrNull { candidate ->
                guides.firstNotNullOfOrNull { guide -> guide.epgKeysByName[candidate]?.let(guide.programsByEpgKey::get) }
            }
        } ?: nameKey.takeIf(String::isNotBlank)?.let { name ->
            guides.firstNotNullOfOrNull { guide -> guide.epgKeysByName[name]?.let(guide.programsByEpgKey::get) }
        }
        if (!programs.isNullOrEmpty()) {
            result[channel.id] = programs
        }
    }
    return result
}

internal fun List<LiveTvEpgProgram>.currentProgram(nowMillis: Long): LiveTvEpgProgram? =
    firstOrNull { it.isAiringAt(nowMillis) }

internal fun List<LiveTvEpgProgram>.nextProgram(nowMillis: Long): LiveTvEpgProgram? =
    firstOrNull { it.startMillis >= nowMillis }

internal fun List<LiveTvEpgProgram>.upcomingPrograms(nowMillis: Long): List<LiveTvEpgProgram> =
    filter { it.endMillis > nowMillis }

internal fun liveTvEpgKey(value: String): String = value.trim().lowercase()

internal fun normalizeEpgChannelName(value: String): String {
    val withoutBrackets = StringBuilder(value.length)
    var depth = 0
    value.lowercase().forEach { char ->
        when (char) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> if (depth > 0) depth--
            else -> if (depth == 0) withoutBrackets.append(char)
        }
    }
    var name = withoutBrackets.toString().trim()
    val prefixSeparator = name.indexOfFirst { it == ':' || it == '|' }
    if (prefixSeparator in 1..5 && prefixSeparator < name.length - 1) {
        name = name.substring(prefixSeparator + 1)
    }
    val tokens = mutableListOf<String>()
    val token = StringBuilder()
    name.forEach { char ->
        if (char.isLetterOrDigit()) {
            token.append(char)
        } else if (token.isNotEmpty()) {
            tokens += token.toString()
            token.clear()
        }
    }
    if (token.isNotEmpty()) tokens += token.toString()
    val filtered = tokens.filterNot { it in epgQualityTokens }
    return (filtered.ifEmpty { tokens }).joinToString("")
}

private val epgQualityTokens = setOf(
    "hd", "fhd", "uhd", "sd", "hq", "lq", "4k", "8k", "hevc", "h264", "h265",
    "2160p", "1080p", "1080i", "720p", "576p", "480p", "50fps", "60fps", "backup", "raw",
)

private fun epgIdNameCandidates(id: String): List<String> {
    val trimmed = id.trim()
    val suffix = trimmed.substringAfterLast('.', missingDelimiterValue = "")
    val withoutCountry = if (suffix.length in 2..3 && suffix.all(Char::isLetter)) {
        trimmed.substringBeforeLast('.')
    } else {
        trimmed
    }
    return listOf(normalizeEpgChannelName(withoutCountry), normalizeEpgChannelName(trimmed))
        .filter(String::isNotBlank)
        .distinct()
}

internal fun parseXmltvTime(value: String): Long? {
    val trimmed = value.trim()
    val digits = trimmed.takeWhile(Char::isDigit)
    if (digits.length < 12) return null
    val year = digits.substring(0, 4).toIntOrNull() ?: return null
    val month = digits.substring(4, 6).toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = digits.substring(6, 8).toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    val hour = digits.substring(8, 10).toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = digits.substring(10, 12).toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    val second = if (digits.length >= 14) digits.substring(12, 14).toIntOrNull()?.coerceIn(0, 59) ?: 0 else 0
    val rest = trimmed.substring(digits.length).trim()
    var offsetMinutes = 0
    if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
        val offsetDigits = rest.substring(1).filter(Char::isDigit)
        val offsetHours = offsetDigits.take(2).toIntOrNull()
        val offsetMins = offsetDigits.drop(2).take(2).toIntOrNull() ?: 0
        if (offsetHours != null) {
            offsetMinutes = (offsetHours * 60 + offsetMins) * if (rest[0] == '-') -1 else 1
        }
    }
    val epochDays = epgDaysFromCivil(year, month, day)
    val epochSeconds = epochDays * 86_400L + hour * 3_600L + minute * 60L + second - offsetMinutes * 60L
    return epochSeconds * 1000L
}

private fun epgDaysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era.toLong() * 146_097L + dayOfEra - 719_468L
}

private fun isXmlTagBoundary(char: Char?): Boolean =
    char == null || char == '>' || char == '/' || char.isWhitespace()

private fun readXmlAttribute(tag: String, name: String): String? {
    var from = 0
    while (true) {
        val index = tag.indexOf(name, from)
        if (index < 0) return null
        var cursor = index + name.length
        val before = tag.getOrNull(index - 1)
        if (before == null || !before.isWhitespace()) {
            from = cursor
            continue
        }
        while (cursor < tag.length && tag[cursor].isWhitespace()) cursor++
        if (tag.getOrNull(cursor) != '=') {
            from = index + name.length
            continue
        }
        cursor++
        while (cursor < tag.length && tag[cursor].isWhitespace()) cursor++
        val quote = tag.getOrNull(cursor)
        if (quote != '"' && quote != '\'') {
            from = cursor
            continue
        }
        val end = tag.indexOf(quote, cursor + 1)
        if (end < 0) return null
        return tag.substring(cursor + 1, end)
    }
}

private fun readXmlElementTexts(
    body: String,
    name: String,
    limit: Int = Int.MAX_VALUE,
): List<String> {
    val results = mutableListOf<String>()
    val open = "<$name"
    val close = "</$name>"
    var index = 0
    while (results.size < limit) {
        val start = body.indexOf(open, index)
        if (start < 0) break
        if (!isXmlTagBoundary(body.getOrNull(start + open.length))) {
            index = start + open.length
            continue
        }
        val tagEnd = body.indexOf('>', start)
        if (tagEnd < 0) break
        if (body[tagEnd - 1] == '/') {
            index = tagEnd + 1
            continue
        }
        val end = body.indexOf(close, tagEnd)
        if (end < 0) break
        val text = decodeXmlText(body.substring(tagEnd + 1, end)).trim()
        if (text.isNotEmpty()) results += text
        index = end + close.length
    }
    return results
}

private fun decodeXmlText(raw: String): String {
    if (!raw.contains("<![CDATA[")) return decodeXmlEntities(raw)
    val builder = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        val cdataStart = raw.indexOf("<![CDATA[", index)
        if (cdataStart < 0) {
            builder.append(decodeXmlEntities(raw.substring(index)))
            break
        }
        builder.append(decodeXmlEntities(raw.substring(index, cdataStart)))
        val contentStart = cdataStart + 9
        val cdataEnd = raw.indexOf("]]>", contentStart)
        if (cdataEnd < 0) {
            builder.append(raw.substring(contentStart))
            break
        }
        builder.appendRange(raw, contentStart, cdataEnd)
        index = cdataEnd + 3
    }
    return builder.toString()
}

internal fun decodeXmlEntities(value: String): String {
    if ('&' !in value) return value
    val builder = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '&') {
            val semicolon = value.indexOf(';', index + 1)
            if (semicolon > index && semicolon - index <= 10) {
                val entity = value.substring(index + 1, semicolon)
                val decoded = when {
                    entity == "amp" -> "&"
                    entity == "lt" -> "<"
                    entity == "gt" -> ">"
                    entity == "quot" -> "\""
                    entity == "apos" -> "'"
                    entity.startsWith("#x") || entity.startsWith("#X") ->
                        entity.substring(2).toIntOrNull(16)?.let(::xmlCodePointToString)
                    entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let(::xmlCodePointToString)
                    else -> null
                }
                if (decoded != null) {
                    builder.append(decoded)
                    index = semicolon + 1
                    continue
                }
            }
        }
        builder.append(char)
        index++
    }
    return builder.toString()
}

private fun xmlCodePointToString(codePoint: Int): String? = when (codePoint) {
    in 0..0xFFFF -> codePoint.toChar().toString()
    in 0x10000..0x10FFFF -> {
        val value = codePoint - 0x10000
        charArrayOf(((value shr 10) + 0xD800).toChar(), ((value and 0x3FF) + 0xDC00).toChar()).concatToString()
    }
    else -> null
}
