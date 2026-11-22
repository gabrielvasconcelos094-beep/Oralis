package app.oralis.generation

import app.oralis.core.text.BreakKind

/**
 * Reading presets only adjust parameters the engine really supports: Kokoro's speaking rate and
 * the silence Oralis inserts between segments. Kokoro exposes no emotion or intonation controls,
 * so none are pretended here.
 *
 * Every Kokoro segment already carries ~0.2 s of natural silence at each edge (measured), i.e.
 * ~0.4 s between consecutive segments. The values below are EXTRA silence on top of that.
 */
enum class ReadingPreset(
    val id: String,
    val label: String,
    val description: String,
    val speakingRate: Float,
    private val extraSentenceMs: Long,
    private val extraLineMs: Long,
    private val extraParagraphMs: Long,
) {
    LESSON("aula", "Aula", "Ritmo claro e pausas discretas entre parágrafos.", 0.95f, 0, 250, 550),
    READING("leitura", "Leitura", "Leitura contínua e equilibrada.", 1.0f, 0, 200, 400),
    NIGHT("noturno", "Noturno", "Mais lento, com pausas longas para uma escuta relaxada.", 0.88f, 150, 450, 1100);

    fun extraPauseMs(kind: BreakKind): Long = when (kind) {
        BreakKind.START -> 0
        BreakKind.SENTENCE -> extraSentenceMs
        BreakKind.LINE -> extraLineMs
        BreakKind.PARAGRAPH -> extraParagraphMs
    }

    /** Natural gap (~400 ms) plus the preset's extra paragraph pause, for duration estimates. */
    val paragraphGapMs: Long get() = 400 + extraParagraphMs

    companion object {
        fun fromId(id: String?): ReadingPreset = entries.firstOrNull { it.id == id } ?: READING
    }
}
