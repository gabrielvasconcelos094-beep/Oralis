package app.oralis.core.text

/**
 * Builds the copy of a text slice that is handed to the speech engine. This is the only
 * transformation Oralis applies, and it is purely technical: it never changes, adds, removes or
 * reorders words. The original text stored in the project is never touched.
 *
 * - Line breaks inside a slice (hard-wrapped lines) become spaces.
 * - Optionally, Markdown *formatting markers* (not words) are removed, because texts produced by
 *   chat assistants often contain them: heading hashes, list bullets, emphasis asterisks or
 *   underscores, inline code backticks, strike-through tildes and link targets `[text](url)` →
 *   `text`. Horizontal rules ("---") become silence.
 * - Runs of whitespace are collapsed.
 */
class SpeechTextPreparer(private val stripMarkdown: Boolean = true) {

    fun prepare(slice: String): String {
        var s = slice.replace("\r\n", "\n").replace('\r', '\n')
        if (stripMarkdown) s = stripMarkdownMarkers(s)
        s = s.replace(WHITESPACE, " ").trim()
        // A slice without any letter or digit (e.g. "***", "—", emoji only) has nothing to read.
        return if (s.any { it.isLetterOrDigit() }) s else ""
    }

    private fun stripMarkdownMarkers(input: String): String {
        val lines = input.split('\n').map { line ->
            var l = line
            if (HR.matches(l)) return@map ""
            l = HEADING.replace(l, "")
            l = BLOCKQUOTE.replace(l, "")
            l = BULLET.replace(l, "")
            l = l.replace(LINK) { it.groupValues[1] }
            l = l.replace(BOLD_ITALIC, "$2")
            l = l.replace(STRIKE, "$1")
            l = l.replace(CODE, "$1")
            l = l.replace(EMPHASIS_STAR, "$1")
            l = l.replace(EMPHASIS_UNDERSCORE, "$1")
            l
        }
        return lines.joinToString("\n")
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val HR = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
        val HEADING = Regex("^\\s{0,3}#{1,6}\\s+")
        val BLOCKQUOTE = Regex("^\\s{0,3}(>\\s?)+")
        val BULLET = Regex("^\\s*[-*+•]\\s+")
        val LINK = Regex("\\[([^\\]\\n]+)]\\(([^)\\s]+)\\)")
        val BOLD_ITALIC = Regex("(\\*\\*\\*|\\*\\*|___|__)(\\S(?:.*?\\S)?)\\1")
        val STRIKE = Regex("~~(\\S(?:.*?\\S)?)~~")
        val CODE = Regex("`+([^`]+)`+")
        val EMPHASIS_STAR = Regex("(?<![\\w*])\\*(\\S(?:[^*]*?\\S)?)\\*(?![\\w*])")
        val EMPHASIS_UNDERSCORE = Regex("(?<![\\w_])_(\\S(?:[^_]*?\\S)?)_(?![\\w_])")
    }
}
