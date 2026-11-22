package app.oralis.model

/**
 * Official Kokoro-82M LiteRT-LM bundles published by Google's `litert-community` on Hugging Face.
 * Downloads are pinned to an immutable repository revision so the SHA-256 below (the Git LFS
 * object id reported by Hugging Face for that revision, also used by Google's official MediaPipe
 * TTS sample for the universal file) always matches.
 *
 * Source: https://huggingface.co/litert-community/Kokoro-82M (Apache-2.0)
 */
enum class ModelVariant(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val title: String,
    val description: String,
    /** Whether the bundle produces clean audio on the CPU (XNNPACK) path. */
    val cpuCapable: Boolean,
) {
    UNIVERSAL(
        id = "universal",
        fileName = "kokoro_int8_fp16.litertlm",
        sizeBytes = 294_783_298L,
        sha256 = "e7b931ee5d4b7104012a838c9eee240d19ea69cb3ca269041a3274f83dbb7fa1",
        title = "Universal",
        description = "Áudio limpo em qualquer aparelho: usa a GPU quando disponível e a CPU caso contrário. Usa mais memória (até ~0,95 GB).",
        cpuCapable = true,
    ),
    GPU(
        id = "gpu",
        fileName = "kokoro_int8_gpu_fp16.litertlm",
        sizeBytes = 294_750_530L,
        sha256 = "1ef178b127ec48c737daf17eabb4cea5f6b3ffbc510aced270fe5c267bbeac52",
        title = "Otimizado para GPU",
        description = "Mais rápido e com metade da memória, mas exige GPU compatível (OpenCL). Sem GPU, o áudio fica com mais ruído.",
        cpuCapable = false,
    );

    val url: String get() = "https://huggingface.co/$REPO/resolve/$REVISION/$fileName"

    companion object {
        const val REPO = "litert-community/Kokoro-82M"
        const val REVISION = "bf1c2405642aa310bd210205c22d3ae1abc253fb"
        val DEFAULT = UNIVERSAL

        fun fromId(id: String?): ModelVariant = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

sealed interface ModelState {
    data object Checking : ModelState
    data object NotInstalled : ModelState
    data class Downloading(val variant: ModelVariant, val bytes: Long, val total: Long, val bytesPerSecond: Long = 0) : ModelState {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Paused(val variant: ModelVariant, val bytes: Long, val total: Long) : ModelState
    data class Verifying(val variant: ModelVariant) : ModelState
    data class Installed(val variant: ModelVariant, val path: String) : ModelState
    data class Failed(val variant: ModelVariant?, val error: ModelError, val bytes: Long = 0) : ModelState
}

enum class ModelError(val message: String, val action: String) {
    NO_NETWORK("Sem conexão com a internet. A instalação continua assim que houver rede.", "Tentar novamente"),
    NETWORK("O download foi interrompido. O que já foi baixado foi mantido.", "Retomar"),
    NO_SPACE("Espaço insuficiente. O modelo precisa de cerca de 300 MB livres.", "Tentar novamente"),
    CORRUPTED("O arquivo baixado não passou na verificação de integridade (SHA-256) e foi descartado.", "Baixar de novo"),
    SERVER("O servidor do modelo respondeu com um erro. Tente mais tarde.", "Tentar novamente"),
    UNKNOWN("Não foi possível instalar o modelo.", "Tentar novamente"),
}
