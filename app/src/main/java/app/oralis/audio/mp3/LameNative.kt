package app.oralis.audio.mp3

/** JNI bindings to the bundled LAME 3.100 encoder (see src/main/cpp/oralis_mp3.c). */
internal object LameNative {
    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("mp3lame")
        System.loadLibrary("oralis_mp3")
        loaded = true
    }

    @JvmStatic external fun nativeInit(sampleRate: Int, bitrateKbps: Int, quality: Int): Long
    @JvmStatic external fun nativeEncode(handle: Long, pcm: ShortArray, count: Int, out: ByteArray): Int
    @JvmStatic external fun nativeFlush(handle: Long, out: ByteArray): Int
    @JvmStatic external fun nativeLameTag(handle: Long, out: ByteArray): Int
    @JvmStatic external fun nativeEncoderDelay(handle: Long): Int
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativeVersion(): String
}
