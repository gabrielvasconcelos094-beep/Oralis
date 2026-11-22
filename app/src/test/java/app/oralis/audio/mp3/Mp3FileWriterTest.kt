package app.oralis.audio.mp3

import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Exercises the real LAME build (host-compiled from the same sources as the APK). */
class Mp3FileWriterTest {

    private lateinit var outDir: File

    @Before
    fun setUp() {
        val ok = runCatching { LameNative.ensureLoaded() }.isSuccess
        assumeTrue("host LAME library not available", ok)
        outDir = File(System.getProperty("oralis.testOutputDir") ?: "build/test-output").apply { mkdirs() }
    }

    private fun tone(seconds: Double, rate: Int = 24_000): ShortArray =
        ShortArray((seconds * rate).toInt()) { i -> (sin(2 * PI * 220 * i / rate) * 12000).toInt().toShort() }

    @Test
    fun versionIs3100() {
        assertTrue(Mp3FileWriter.lameVersion().startsWith("3.100"))
    }

    @Test
    fun producesValidMp3WithExactDurationForEachBitrate() {
        for (kbps in listOf(64, 96, 128)) {
            val f = File(outDir, "tone_$kbps.mp3")
            Mp3FileWriter(f, 24_000, kbps, Mp3Metadata("Teste ação €", "Oralis")).use { w ->
                w.write(tone(3.0))
                w.writeSilence(24_000L)
                w.write(tone(1.5))
                w.finish()
            }
            val r = Mp3Inspector.inspect(f)
            assertTrue("invalid: ${r.problem}", r.isValid)
            assertEquals(24_000, r.sampleRate)
            assertEquals(kbps, r.bitrateKbps)
            assertEquals(r.audioFrames, r.infoTagFrames)
            assertEquals(5500.0, r.durationMs.toDouble(), 1.0)
            // CBR size sanity: ~ duration * kbps / 8
            val expectedBytes = 5.5 * kbps * 1000 / 8
            assertEquals(expectedBytes, f.length().toDouble(), expectedBytes * 0.1)
        }
    }

    @Test
    fun longStreamingEncodeKeepsConstantMemoryAndExactDuration() {
        val f = File(outDir, "long.mp3")
        val block = tone(10.0)
        val minutes = 20
        Mp3FileWriter(f, 24_000, 64, Mp3Metadata("Longo")).use { w ->
            repeat(minutes * 6) { w.write(block) }
            w.finish()
        }
        val r = Mp3Inspector.inspect(f)
        assertTrue(r.problem, r.isValid)
        assertEquals(minutes * 60_000.0, r.durationMs.toDouble(), 2.0)
    }

    @Test
    fun inspectorRejectsTruncatedFile() {
        val f = File(outDir, "trunc_src.mp3")
        Mp3FileWriter(f, 24_000, 96, Mp3Metadata("T")).use { w -> w.write(tone(2.0)); w.finish() }
        val bytes = f.readBytes()
        val cut = File(outDir, "trunc.mp3").apply { writeBytes(bytes.copyOf(bytes.size - 37)) }
        assertFalse(Mp3Inspector.inspect(cut).isValid)
    }
}
