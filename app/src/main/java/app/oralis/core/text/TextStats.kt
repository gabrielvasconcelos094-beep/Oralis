package app.oralis.core.text

import kotlin.math.roundToLong

data class TextStats(val characters: Int, val words: Int, val paragraphs: Int)

object TextAnalysis {
    private val WORD = Regex("[\\p{L}\\p{N}]+(?:[’'\\-][\\p{L}\\p{N}]+)*")
    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")

    fun stats(text: String): TextStats {
        if (text.isBlank()) return TextStats(text.length, 0, 0)
        val words = WORD.findAll(text).count()
        val paragraphs = text.trim().split(PARAGRAPH_BREAK).count { it.isNotBlank() }
        return TextStats(text.length, words, paragraphs)
    }

    /**
     * Rough narration length. Base rates were measured with Kokoro-82M at speakingRate 1.0
     * (pt-BR "pf_dora": ~155 words/min on continuous prose; en-GB voices: ~165 words/min), plus the
     * extra pauses inserted between paragraphs. It is an estimate, shown as such in the UI.
     */
    fun estimateDurationMs(stats: TextStats, languageTag: String, speakingRate: Float, paragraphPauseMs: Long): Long {
        if (stats.words == 0) return 0
        val wpm = if (languageTag.startsWith("pt")) 155.0 else 165.0
        val speechMs = stats.words / (wpm * speakingRate) * 60_000.0
        return (speechMs + (stats.paragraphs - 1).coerceAtLeast(0) * paragraphPauseMs).roundToLong()
    }
}
