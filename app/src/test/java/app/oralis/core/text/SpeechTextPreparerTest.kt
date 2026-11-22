package app.oralis.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextPreparerTest {
    private val p = SpeechTextPreparer(stripMarkdown = true)
    private val raw = SpeechTextPreparer(stripMarkdown = false)

    @Test
    fun removesOnlyFormattingMarkers() {
        assertEquals("Isto é muito importante.", p.prepare("Isto é **muito** importante."))
        assertEquals("Introdução", p.prepare("## Introdução"))
        assertEquals("primeiro item", p.prepare("- primeiro item"))
        assertEquals("Veja a documentação aqui.", p.prepare("Veja a [documentação](https://x.org/doc) aqui."))
        assertEquals("use print e itálico", p.prepare("use `print` e *itálico*"))
        assertEquals("citação", p.prepare("> citação"))
        assertEquals("", p.prepare("---"))
    }

    @Test
    fun keepsMeaningfulSymbols() {
        assertEquals("2 * 3 = 6", p.prepare("2 * 3 = 6"))
        assertEquals("snake_case_name e 5%", p.prepare("snake_case_name e 5%"))
        assertEquals("1. Primeiro passo", p.prepare("1. Primeiro passo"))
        assertEquals("— Olá, disse ela.", p.prepare("— Olá, disse ela."))
    }

    @Test
    fun withoutStrippingTextIsOnlyWhitespaceNormalized() {
        assertEquals("Isto é **muito** importante.", raw.prepare("Isto é\n**muito**   importante."))
    }

    @Test
    fun wordsAreNeverAltered() {
        val text = "O conhecimento — segundo Espinosa — é a via da liberdade; não há outra."
        assertEquals(text, p.prepare(text))
    }
}
