package app.oralis.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewWavTest {
    @Test
    fun writesValidMono16BitWav() {
        val samples = FloatArray(24_000 * 3) { (sin(it * 0.05) * 0.5).toFloat() }
        val f = File(System.getProperty("oralis.testOutputDir") ?: "build/test-output", "preview.wav").apply { parentFile.mkdirs() }
        VoicePreviewPlayer.writeWav(f, samples, 24_000)
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(f.readBytes(), 0, 4))
        assertEquals(f.length() - 8, b.getInt(4).toLong())
        assertEquals(1, b.getShort(22).toInt())        // mono
        assertEquals(24_000, b.getInt(24))             // sample rate
        assertEquals(16, b.getShort(34).toInt())       // bits
        assertEquals(samples.size * 2, b.getInt(40))   // data size
        assertEquals(44L + samples.size * 2, f.length())
    }
}
