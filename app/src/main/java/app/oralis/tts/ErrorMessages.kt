package app.oralis.tts

import app.oralis.generation.GenerationFailure

/** User-facing explanations (no stack traces, no technical jargon). */
object ErrorMessages {
    fun engine(e: SpeechEngineException): String = when (e.kind) {
        SpeechEngineException.Kind.MODEL_MISSING ->
            "O modelo de voz não está instalado. Instale-o na tela inicial."
        SpeechEngineException.Kind.MODEL_INCOMPATIBLE ->
            "Este aparelho não conseguiu usar a versão do modelo instalada. Em Configurações › Modelo de voz, instale a versão Universal."
        SpeechEngineException.Kind.INIT_FAILED ->
            "Não foi possível iniciar o motor de voz. Feche outros aplicativos e tente de novo; se persistir, verifique a integridade do modelo nas Configurações."
        SpeechEngineException.Kind.OUT_OF_MEMORY ->
            "Memória insuficiente para o motor de voz. Feche outros aplicativos e tente novamente."
        SpeechEngineException.Kind.SYNTHESIS_FAILED ->
            "O motor de voz falhou ao ler um trecho. O progresso foi salvo; tente retomar."
    }

    fun generation(e: GenerationFailure): String = when (e.kind) {
        GenerationFailure.Kind.NO_SPEAKABLE_TEXT -> "O texto não contém palavras para ler."
        GenerationFailure.Kind.INSUFFICIENT_STORAGE -> "Espaço de armazenamento insuficiente. Libere espaço e retome: o progresso foi salvo."
        GenerationFailure.Kind.ENCODING_FAILED -> "Falha ao criar o arquivo MP3. O áudio sintetizado foi preservado; tente retomar."
        GenerationFailure.Kind.VALIDATION_FAILED -> "O MP3 gerado não passou na verificação e foi descartado. Tente retomar."
        GenerationFailure.Kind.TEXT_CHANGED -> "O texto original foi alterado."
    }
}
