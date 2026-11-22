package app.oralis.audio.mp3

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/**
 * Independent structural validator for MP3 files produced by Oralis. It walks every MPEG audio
 * frame, checks that the stream is continuous (no garbage between frames, no truncated last
 * frame) and reads the LAME Info tag to compute the exact playable duration.
 */
object Mp3Inspector {

    data class Report(
        val isValid: Boolean,
        val problem: String?,
        val sampleRate: Int,
        val bitrateKbps: Int,
        val audioFrames: Long,
        val infoTagFrames: Long?,
        val encoderDelay: Int,
        val encoderPadding: Int,
        val durationMs: Long,
        val id3Bytes: Int,
    )

    private val BITRATES_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BITRATES_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val RATES_V1 = intArrayOf(44100, 48000, 32000)
    private val RATES_V2 = intArrayOf(22050, 24000, 16000)
    private val RATES_V25 = intArrayOf(11025, 12000, 8000)

    private data class Header(
        val mpeg1: Boolean, val sampleRate: Int, val bitrate: Int, val size: Int,
        val samplesPerFrame: Int, val mono: Boolean,
    )

    private fun parseHeader(b0: Int, b1: Int, b2: Int, b3: Int): Header? {
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
        val version = (b1 shr 3) and 3 // 0: 2.5, 2: 2, 3: 1
        val layer = (b1 shr 1) and 3   // 1: Layer III
        if (version == 1 || layer != 1) return null
        val brIdx = (b2 shr 4) and 0xF
        val srIdx = (b2 shr 2) and 3
        if (brIdx == 0 || brIdx == 15 || srIdx == 3) return null
        val padding = (b2 shr 1) and 1
        val mpeg1 = version == 3
        val bitrate = if (mpeg1) BITRATES_V1[brIdx] else BITRATES_V2[brIdx]
        val sr = when (version) {
            3 -> RATES_V1[srIdx]
            2 -> RATES_V2[srIdx]
            else -> RATES_V25[srIdx]
        }
        val size = (if (mpeg1) 144 else 72) * bitrate * 1000 / sr + padding
        val mono = ((b3 shr 6) and 3) == 3
        return Header(mpeg1, sr, bitrate, size, if (mpeg1) 1152 else 576, mono)
    }

    fun inspect(file: File): Report = file.inputStream().buffered(1 shl 16).use { inspect(it, file.length()) }

    fun inspect(input: InputStream, totalLength: Long): Report {
        val stream = if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16)
        var pos = 0L

        fun fail(msg: String, sr: Int = 0, br: Int = 0, frames: Long = 0, id3: Int = 0) =
            Report(false, msg, sr, br, frames, null, 0, 0, 0, id3)

        // ID3v2
        val head = ByteArray(10)
        stream.mark(16)
        if (stream.readNBytesCompat(head) < 10) return fail("Arquivo vazio ou truncado")
        var id3Size = 0
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            id3Size = 10 + ((head[6].toInt() and 0x7F) shl 21 or ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F))
            stream.reset()
            stream.skipFully(id3Size.toLong())
            pos = id3Size.toLong()
        } else {
            stream.reset()
        }

        var frames = 0L
        var sampleRate = 0
        var bitrate = 0
        var spf = 0
        var infoFrames: Long? = null
        var delay = 0
        var padding = 0
        var first = true
        val frameBuf = ByteArray(4096)
        while (pos < totalLength) {
            if (totalLength - pos < 128 && frames > 0) {
                // Possible ID3v1 trailer.
                stream.mark(4)
                val t = ByteArray(3)
                if (stream.readNBytesCompat(t) == 3 && String(t, Charsets.ISO_8859_1) == "TAG") break
                stream.reset()
            }
            if (stream.readNBytesCompat(frameBuf, 4) < 4) return fail("Frame truncado no final", sampleRate, bitrate, frames, id3Size)
            val h = parseHeader(
                frameBuf[0].toInt() and 0xFF, frameBuf[1].toInt() and 0xFF,
                frameBuf[2].toInt() and 0xFF, frameBuf[3].toInt() and 0xFF,
            ) ?: return fail("Perda de sincronismo no byte $pos", sampleRate, bitrate, frames, id3Size)
            if (sampleRate != 0 && h.sampleRate != sampleRate) return fail("Taxa de amostragem inconsistente", sampleRate, bitrate, frames, id3Size)
            val rest = h.size - 4
            if (rest > frameBuf.size - 4) return fail("Frame inválido", sampleRate, bitrate, frames, id3Size)
            val got = stream.readNBytesCompat(frameBuf, rest, offset = 4)
            if (got < rest) return fail("Último frame incompleto", h.sampleRate, h.bitrate, frames, id3Size)
            if (first) {
                first = false
                sampleRate = h.sampleRate
                spf = h.samplesPerFrame
                val sideInfo = if (h.mpeg1) (if (h.mono) 17 else 32) else (if (h.mono) 9 else 17)
                val off = 4 + sideInfo
                val tag = String(frameBuf, off, 4, Charsets.ISO_8859_1)
                if (tag == "Info" || tag == "Xing") {
                    val flags = readInt(frameBuf, off + 4)
                    var p = off + 8
                    if (flags and 1 != 0) { infoFrames = readInt(frameBuf, p).toLong() and 0xFFFFFFFFL; p += 4 }
                    val lameOff = off + 120
                    if (lameOff + 24 <= h.size && String(frameBuf, lameOff, 4, Charsets.ISO_8859_1) == "LAME") {
                        val d = lameOff + 21
                        val x = frameBuf[d].toInt() and 0xFF
                        val y = frameBuf[d + 1].toInt() and 0xFF
                        val z = frameBuf[d + 2].toInt() and 0xFF
                        delay = (x shl 4) or (y shr 4)
                        padding = ((y and 0x0F) shl 8) or z
                    }
                    pos += h.size
                    continue // the Info frame carries no audio
                }
            }
            bitrate = h.bitrate
            frames++
            pos += h.size
        }
        if (frames == 0L) return fail("Nenhum frame de áudio", sampleRate, bitrate, 0, id3Size)
        if (infoFrames != null && infoFrames != frames) {
            return fail("Tag Info indica $infoFrames frames, arquivo tem $frames", sampleRate, bitrate, frames, id3Size)
        }
        val totalSamples = (frames * spf - delay - padding).coerceAtLeast(0)
        return Report(true, null, sampleRate, bitrate, frames, infoFrames, delay, padding,
            totalSamples * 1000 / sampleRate, id3Size)
    }

    private fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun InputStream.readNBytesCompat(buf: ByteArray, len: Int = buf.size, offset: Int = 0): Int {
        var read = 0
        while (read < len) {
            val r = read(buf, offset + read, len - read)
            if (r < 0) break
            read += r
        }
        return read
    }

    private fun InputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) { if (read() < 0) return; left-- } else left -= s
        }
    }
}
