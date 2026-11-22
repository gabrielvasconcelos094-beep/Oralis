package app.oralis.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSegmenterTest {

    private val segmenter = TextSegmenter(maxChars = 120)

    /** Core fidelity property: segments cover every non-blank char exactly once, in order. */
    private fun assertFaithful(text: String, segments: List<TextSegment>) {
        var cursor = 0
        for ((i, s) in segments.withIndex()) {
            assertEquals("index", i, s.index)
            assertTrue("order/overlap at segment $i", s.start >= cursor)
            assertTrue("gap before segment $i must be whitespace", text.substring(cursor, s.start).isBlank())
            assertTrue(s.end > s.start)
            cursor = s.end
        }
        assertTrue("tail must be whitespace", text.substring(cursor).isBlank())
        val rebuilt = segments.joinToString(" ") { text.substring(it.start, it.end) }
        assertEquals(text.split(Regex("\\s+")).filter { it.isNotEmpty() }, rebuilt.split(Regex("\\s+")).filter { it.isNotEmpty() })
    }

    @Test
    fun emptyAndBlankTextProduceNoSegments() {
        assertTrue(segmenter.segment("").isEmpty())
        assertTrue(segmenter.segment("  \n\n \t ").isEmpty())
    }

    @Test
    fun preservesParagraphsAndOrder() {
        val text = "Primeiro parágrafo. Tem duas frases.\n\nSegundo parágrafo, mais curto.\n\n\nTerceiro."
        val segs = segmenter.segment(text)
        assertFaithful(text, segs)
        assertEquals(BreakKind.START, segs.first().breakBefore)
        assertEquals(listOf(BreakKind.START, BreakKind.PARAGRAPH, BreakKind.PARAGRAPH), segs.map { it.breakBefore })
        assertEquals("Terceiro.", segs.last().speechText)
    }

    @Test
    fun singleLineBreaksAreLineBreaks() {
        val text = "Introdução\nO texto começa aqui.\n- item um\n- item dois"
        val segs = segmenter.segment(text)
        assertFaithful(text, segs)
        assertEquals(listOf(BreakKind.START, BreakKind.LINE, BreakKind.LINE, BreakKind.LINE), segs.map { it.breakBefore })
        assertEquals("item um", segs[2].speechText)
    }

    @Test
    fun hardWrappedLinesAreJoined() {
        val text = "Este parágrafo foi copiado de um PDF e\nquebrado no meio da frase, mas deve\nser lido de forma contínua."
        val segs = segmenter.segment(text)
        assertFaithful(text, segs)
        assertEquals(1, segs.size)
        assertEquals("Este parágrafo foi copiado de um PDF e quebrado no meio da frase, mas deve ser lido de forma contínua.", segs[0].speechText)
    }

    @Test
    fun keepsAccentsAndPunctuationIntact() {
        val text = "Ação, emoção e razão — não é? «Sim!», disse ele… Então: 3,5% em 1922; ok."
        val segs = TextSegmenter(600).segment(text)
        assertFaithful(text, segs)
        assertEquals(text, segs.joinToString(" ") { it.speechText })
    }

    @Test
    fun longSentenceIsSplitAtClausesNeverInsideWords() {
        val sentence = (1..40).joinToString(", ") { "palavra$it" } + "."
        val segs = segmenter.segment(sentence)
        assertFaithful(sentence, segs)
        assertTrue(segs.size > 1)
        for (s in segs) {
            assertTrue(s.end - s.start <= 120)
            val before = if (s.start > 0) sentence[s.start - 1] else ' '
            assertTrue("cut inside a word at ${s.start}", before.isWhitespace())
        }
    }

    @Test
    fun hugeTokenIsNeverCut() {
        val url = "https://example.com/" + "a".repeat(300)
        val text = "Veja $url agora."
        val segs = segmenter.segment(text)
        assertFaithful(text, segs)
        assertTrue(segs.any { text.substring(it.start, it.end).contains(url) })
    }

    @Test
    fun abbreviationsDoNotSplitSentences() {
        val pt = "O Dr. Silva e a Sra. Souza chegaram. Depois saíram."
        val parts = segmenter.splitSentences(pt, 0, pt.length)
        assertEquals(2, parts.size)
        val en = "Mr. Smith met J. R. R. Tolkien. They talked."
        assertEquals(2, segmenter.splitSentences(en, 0, en.length).size)
    }

    @Test
    fun sentencesAreGroupedUpToTheLimit() {
        val text = (1..30).joinToString(" ") { "Frase número $it." }
        val segs = segmenter.segment(text)
        assertFaithful(text, segs)
        assertTrue(segs.all { it.end - it.start <= 120 })
        assertTrue(segs.all { text.substring(it.start, it.end).endsWith(".") })
        assertTrue(segs.drop(1).all { it.breakBefore == BreakKind.SENTENCE })
    }

    @Test
    fun veryLongTextHasNoDuplicatesOrGaps() {
        val para = "A história do cinema começa, de certo modo, com a lanterna mágica. " +
            "Depois vieram o cinematógrafo, o som, a cor e o digital! Será o fim? Não…"
        val text = (1..2000).joinToString("\n\n") { "Capítulo $it. $para" }
        val segs = TextSegmenter().segment(text)
        assertFaithful(text, segs)
        val distinctRanges = segs.map { it.start to it.end }.toSet()
        assertEquals(segs.size, distinctRanges.size)
        assertTrue(segs.all { it.end - it.start <= TextSegmenter.DEFAULT_MAX_CHARS })
    }

    @Test
    fun englishAndMixedText() {
        val text = "Good evening. This is Oralis!\n\nIt reads Portuguese, English and more: “quotes” included."
        val segs = segmenter.segment(text)
        assertFaithful(text, segs)
        assertFalse(segs.any { it.isSilent })
    }

    @Test
    fun windowsLineEndings() {
        val text = "Linha um.\r\nLinha dois.\r\n\r\nParágrafo."
        val segs = segmenter.segment(text)
        assertFaithful(text, segs)
        assertEquals(listOf(BreakKind.START, BreakKind.LINE, BreakKind.PARAGRAPH), segs.map { it.breakBefore })
    }

    @Test
    fun markdownRuleBecomesSilentSegment() {
        val text = "Antes.\n\n---\n\nDepois."
        val segs = segmenter.segment(text)
        assertFaithful(text, segs)
        assertEquals(3, segs.size)
        assertTrue(segs[1].isSilent)
    }
}
