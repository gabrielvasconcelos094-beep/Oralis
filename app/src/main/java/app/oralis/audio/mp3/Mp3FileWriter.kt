package app.oralis.audio.mp3

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class Mp3EncodingException(message: String) : IOException(message)

/** Metadata written as an ID3v2.3 tag so players show a meaningful title. */
data class Mp3Metadata(
    val title: String,
    val artist: String = "Oralis",
    val album: String = "Oralis",
    val comment: String? = null,
)

/**
 * Streams 16-bit mono PCM into a CBR MP3 file with constant memory use.
 *
 * Layout: [ID3v2.3 tag][LAME Info frame][audio frames…]. The Info frame is a placeholder emitted by
 * LAME at the start of the stream and rewritten in [finish] with the real frame count, encoder
 * delay and padding, so players report an exact duration.
 *
 * The output is written to [target] only; callers write to a temporary file and rename it after
 * validation so an incomplete file is never mistaken for a finished audio.
 */
class Mp3FileWriter(
    private val target: File,
    val sampleRate: Int,
    val bitrateKbps: Int,
    metadata: Mp3Metadata,
    quality: Int = 2,
) : Closeable {

    private var handle: Long
    private val raf: RandomAccessFile
    private val audioStart: Long
    private val outBuffer = ByteArray((1.25 * BLOCK_SAMPLES + 7200).toInt() + 1)
    private var finished = false
    var samplesWritten: Long = 0
        private set

    init {
        require(bitrateKbps in SUPPORTED_BITRATES) { "Unsupported bitrate $bitrateKbps" }
        LameNative.ensureLoaded()
        handle = LameNative.nativeInit(sampleRate, bitrateKbps, quality)
        if (handle == 0L) throw Mp3EncodingException("LAME init failed (rate=$sampleRate, kbps=$bitrateKbps)")
        target.parentFile?.mkdirs()
        raf = RandomAccessFile(target, "rw")
        raf.setLength(0)
        raf.write(Id3v2Writer.build(metadata))
        audioStart = raf.filePointer
    }

    /** Encodes [count] samples of [pcm]. Large arrays are processed in blocks. */
    fun write(pcm: ShortArray, count: Int = pcm.size) {
        check(!finished) { "Writer already finished" }
        var offset = 0
        val block = if (pcm.size <= BLOCK_SAMPLES && offset == 0) null else ShortArray(BLOCK_SAMPLES)
        while (offset < count) {
            val n = minOf(BLOCK_SAMPLES, count - offset)
            val src = if (block == null) pcm else {
                System.arraycopy(pcm, offset, block, 0, n); block
            }
            val bytes = LameNative.nativeEncode(handle, src, n, outBuffer)
            if (bytes < 0) throw Mp3EncodingException("LAME encode error $bytes")
            if (bytes > 0) raf.write(outBuffer, 0, bytes)
            offset += n
        }
        samplesWritten += count
    }

    /** Writes [samples] of digital silence. */
    fun writeSilence(samples: Long) {
        if (samples <= 0) return
        val zeros = ShortArray(BLOCK_SAMPLES)
        var left = samples
        while (left > 0) {
            val n = minOf(left, BLOCK_SAMPLES.toLong()).toInt()
            write(zeros, n)
            left -= n
        }
    }

    /** Flushes the encoder, rewrites the Info frame and syncs the file to storage. */
    fun finish() {
        check(!finished) { "Writer already finished" }
        val bytes = LameNative.nativeFlush(handle, outBuffer)
        if (bytes < 0) throw Mp3EncodingException("LAME flush error $bytes")
        if (bytes > 0) raf.write(outBuffer, 0, bytes)
        val tag = ByteArray(2880)
        val tagSize = LameNative.nativeLameTag(handle, tag)
        if (tagSize > 0) {
            raf.seek(audioStart)
            raf.write(tag, 0, tagSize)
        }
        raf.fd.sync()
        finished = true
    }

    override fun close() {
        if (handle != 0L) {
            LameNative.nativeClose(handle)
            handle = 0L
        }
        raf.close()
    }

    companion object {
        const val BLOCK_SAMPLES = 8192
        /** Valid MPEG-2 Layer III bitrates at 24 kHz that Oralis offers. */
        val SUPPORTED_BITRATES = setOf(64, 96, 128)

        fun lameVersion(): String {
            LameNative.ensureLoaded()
            return LameNative.nativeVersion()
        }
    }
}

/** Minimal ID3v2.3 writer (TIT2, TPE1, TALB, TCON, COMM) using UTF-16 text. */
internal object Id3v2Writer {
    fun build(meta: Mp3Metadata): ByteArray {
        val frames = java.io.ByteArrayOutputStream()
        frames.write(textFrame("TIT2", meta.title))
        frames.write(textFrame("TPE1", meta.artist))
        frames.write(textFrame("TALB", meta.album))
        frames.write(textFrame("TCON", "Speech"))
        meta.comment?.let { frames.write(commentFrame(it)) }
        val body = frames.toByteArray()
        val padding = 256
        val size = body.size + padding
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        out.write(synchsafe(size))
        out.write(body)
        out.write(ByteArray(padding))
        return out.toByteArray()
    }

    private fun utf16(text: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

    private fun textFrame(id: String, value: String): ByteArray {
        val payload = byteArrayOf(1) + utf16(value.take(500)) + byteArrayOf(0, 0)
        return header(id, payload.size) + payload
    }

    private fun commentFrame(value: String): ByteArray {
        val payload = byteArrayOf(1) + "por".toByteArray(Charsets.ISO_8859_1) +
            utf16("") + byteArrayOf(0, 0) + utf16(value.take(1000)) + byteArrayOf(0, 0)
        return header("COMM", payload.size) + payload
    }

    private fun header(id: String, size: Int): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(), 0, 0,
        )

    private fun synchsafe(size: Int): ByteArray = byteArrayOf(
        ((size ushr 21) and 0x7F).toByte(), ((size ushr 14) and 0x7F).toByte(),
        ((size ushr 7) and 0x7F).toByte(), (size and 0x7F).toByte(),
    )
}
