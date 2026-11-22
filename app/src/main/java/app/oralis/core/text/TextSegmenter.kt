package app.oralis.core.text

/**
 * What separates a segment from the previous one in the ORIGINAL text. It decides how much
 * silence is inserted between the two audio segments (see [app.oralis.generation.ReadingPreset]).
 */
enum class BreakKind {
    /** First segment of the text. */
    START,

    /** Next sentence(s) of the same paragraph. */
    SENTENCE,

    /** Single line break (list items, headings, verses). */
    LINE,

    /** Blank line(s): a new paragraph. */
    PARAGRAPH,
}

/**
 * A contiguous slice of the original text, identified by offsets. The original string is never
 * modified: [start] and [end] point into it, and [speechText] is the technically prepared copy
 * that is sent to the speech engine (see [SpeechTextPreparer]).
 */
data class TextSegment(
    val index: Int,
    val start: Int,
    val end: Int,
    val breakBefore: BreakKind,
    val speechText: String,
) {
    /** Segments made only of symbols (e.g. a Markdown rule "---") produce silence, not speech. */
    val isSilent: Boolean get() = speechText.isEmpty()
}

/**
 * Splits arbitrarily long text into ordered segments suitable for the TTS engine, respecting
 * paragraphs, line breaks, sentences and – only when a sentence is too long – clause punctuation
 * or whitespace. Words are never cut.
 *
 * Guarantees (verified by unit tests):
 *  - segments are in original order and do not overlap;
 *  - every non-whitespace character of the input belongs to exactly one segment;
 *  - nothing is added: each segment's text is a substring of the input.
 */
class TextSegmenter(
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val preparer: SpeechTextPreparer = SpeechTextPreparer(),
) {
    init {
        require(maxChars >= 40) { "maxChars too small" }
    }

    fun segment(text: String): List<TextSegment> {
        val result = ArrayList<TextSegment>()
        for (unit in splitUnits(text)) {
            var kind = unit.breakBefore
            for (range in chunkUnit(text, unit.start, unit.end)) {
                val (s, e) = trim(text, range.first, range.last + 1)
                if (s >= e) continue
                val effectiveKind = if (result.isEmpty()) BreakKind.START else kind
                result += TextSegment(
                    index = result.size,
                    start = s,
                    end = e,
                    breakBefore = effectiveKind,
                    speechText = preparer.prepare(text.substring(s, e)),
                )
                kind = BreakKind.SENTENCE
            }
        }
        return result
    }

    // ------------------------------------------------------------------------------------------
    // 1. Units: paragraphs / lines, with hard-wrapped lines re-joined.
    // ------------------------------------------------------------------------------------------

    private data class Unit(val start: Int, val end: Int, val breakBefore: BreakKind)

    private fun splitUnits(text: String): List<Unit> {
        data class Line(val start: Int, val end: Int, val blankLinesBefore: Int)

        val lines = ArrayList<Line>()
        var pos = 0
        var blanks = 0
        while (pos <= text.length) {
            var nl = text.indexOf('\n', pos)
            if (nl < 0) nl = text.length
            var lineEnd = nl
            if (lineEnd > pos && text[lineEnd - 1] == '\r') lineEnd--
            if (text.substring(pos, lineEnd).isBlank()) {
                blanks++
            } else {
                lines += Line(pos, lineEnd, blanks)
                blanks = 0
            }
            if (nl >= text.length) break
            pos = nl + 1
        }

        val units = ArrayList<Unit>()
        var curStart = -1
        var curEnd = -1
        var curBreak = BreakKind.START
        for ((i, line) in lines.withIndex()) {
            val joinWithPrevious = i > 0 && line.blankLinesBefore == 0 &&
                isHardWrap(text, curEnd, line.start, line.end)
            if (joinWithPrevious) {
                curEnd = line.end
                continue
            }
            if (curStart >= 0) units += Unit(curStart, curEnd, curBreak)
            curStart = line.start
            curEnd = line.end
            curBreak = when {
                i == 0 -> BreakKind.START
                line.blankLinesBefore > 0 -> BreakKind.PARAGRAPH
                else -> BreakKind.LINE
            }
        }
        if (curStart >= 0) units += Unit(curStart, curEnd, curBreak)
        return units
    }

    /**
     * A line break is treated as a hard wrap (text copied from PDFs or e-mails) when the previous
     * line does not end with terminal punctuation and the next line starts in lowercase.
     */
    private fun isHardWrap(text: String, prevEnd: Int, lineStart: Int, lineEnd: Int): Boolean {
        var p = prevEnd - 1
        while (p >= 0 && text[p].isWhitespace()) p--
        if (p < 0) return false
        val last = text[p]
        if (last in TERMINALS || last == ':' || last == ';') return false
        var q = lineStart
        while (q < lineEnd && text[q].isWhitespace()) q++
        if (q >= lineEnd) return false
        val first = text[q]
        return first.isLetter() && first.isLowerCase()
    }

    // ------------------------------------------------------------------------------------------
    // 2. Sentences grouped into chunks of at most maxChars.
    // ------------------------------------------------------------------------------------------

    private fun chunkUnit(text: String, start: Int, end: Int): List<IntRange> {
        val sentences = splitSentences(text, start, end)
        val chunks = ArrayList<IntRange>()
        var chunkStart = -1
        var chunkEnd = -1
        for (s in sentences) {
            val len = s.last + 1 - s.first
            if (len > maxChars) {
                if (chunkStart >= 0) {
                    chunks += chunkStart until chunkEnd
                    chunkStart = -1
                }
                chunks += splitLongSentence(text, s.first, s.last + 1)
                continue
            }
            if (chunkStart < 0) {
                chunkStart = s.first
                chunkEnd = s.last + 1
            } else if (s.last + 1 - chunkStart <= maxChars) {
                chunkEnd = s.last + 1
            } else {
                chunks += chunkStart until chunkEnd
                chunkStart = s.first
                chunkEnd = s.last + 1
            }
        }
        if (chunkStart >= 0) chunks += chunkStart until chunkEnd
        return chunks
    }

    /** Returns contiguous ranges covering [start, end) split after sentence terminators. */
    internal fun splitSentences(text: String, start: Int, end: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var sentenceStart = start
        var i = start
        while (i < end) {
            val c = text[i]
            if (c in TERMINALS) {
                var j = i + 1
                // Absorb repeated terminators and closing quotes/brackets: ?!  ...  ." »)
                while (j < end && (text[j] in TERMINALS || text[j] in CLOSERS)) j++
                val atBoundary = j >= end || text[j].isWhitespace()
                if (atBoundary && !(c == '.' && isAbbreviation(text, sentenceStart, i)) &&
                    !(c == '.' && nextStartsLowercase(text, j, end))
                ) {
                    out += sentenceStart until j
                    sentenceStart = j
                }
                i = j
                continue
            }
            i++
        }
        if (sentenceStart < end) out += sentenceStart until end
        return out
    }

    private fun nextStartsLowercase(text: String, from: Int, end: Int): Boolean {
        var k = from
        while (k < end && text[k].isWhitespace()) k++
        return k < end && text[k].isLetter() && text[k].isLowerCase()
    }

    private fun isAbbreviation(text: String, sentenceStart: Int, dotIndex: Int): Boolean {
        var k = dotIndex - 1
        while (k >= sentenceStart && (text[k].isLetter() || text[k] == '.')) k--
        val word = text.substring(k + 1, dotIndex)
        if (word.isEmpty()) return false
        if (word.length == 1 && word[0].isUpperCase()) return true // initials: "J. R. R."
        return word.lowercase() in ABBREVIATIONS
    }

    /** Splits a sentence longer than maxChars at clause punctuation, then at whitespace. */
    private fun splitLongSentence(text: String, start: Int, end: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var s = start
        while (end - s > maxChars) {
            val limit = s + maxChars
            val minCut = s + maxChars / 3
            var cut = -1
            // Prefer clause punctuation followed by whitespace.
            var k = limit - 1
            while (k > minCut) {
                if (text[k] in CLAUSE && k + 1 < end && text[k + 1].isWhitespace()) {
                    cut = k + 1
                    break
                }
                k--
            }
            if (cut < 0) {
                k = limit
                while (k > s && !text[k].isWhitespace()) k--
                cut = if (k > s) k else {
                    // A single "word" longer than maxChars (e.g. a URL): never cut it.
                    var w = limit
                    while (w < end && !text[w].isWhitespace()) w++
                    w
                }
            }
            out += s until cut
            s = cut
            while (s < end && text[s].isWhitespace()) s++
        }
        if (s < end) out += s until end
        return out
    }

    private fun trim(text: String, start: Int, end: Int): Pair<Int, Int> {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return s to e
    }

    companion object {
        /** ~35–45 s of speech; Kokoro itself sub-splits at ~124 phonemes per inference. */
        const val DEFAULT_MAX_CHARS = 600

        private val TERMINALS = setOf('.', '!', '?', '…', '。', '！', '？')
        private val CLOSERS = setOf('"', '\'', '”', '’', '»', ')', ']', '}')
        private val CLAUSE = setOf(',', ';', ':', '—', '–')

        private val ABBREVIATIONS = setOf(
            // Portuguese
            "sr", "sra", "srta", "dr", "dra", "prof", "profa", "exmo", "exma", "av", "pág", "págs",
            "p", "pp", "cap", "caps", "vol", "vols", "n", "nº", "ed", "eds", "fig", "figs", "obs",
            "aprox", "séc", "sec", "op", "cit", "ibid", "trad", "org", "coord", "tel", "dept",
            // English
            "mr", "mrs", "ms", "st", "vs", "e.g", "i.e", "etc", "no", "jr", "sr", "fig", "approx",
            "dept", "est", "cf",
        )
    }
}
