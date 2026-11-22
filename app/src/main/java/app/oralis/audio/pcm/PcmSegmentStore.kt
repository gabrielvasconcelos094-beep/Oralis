package app.oralis.audio.pcm

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stores each synthesized segment as a raw 16-bit little-endian mono PCM file inside a job's
 * working directory. A segment file only exists under its final name once it has been fully
 * written and fsync'ed (write-to-temp + atomic rename), so its presence is the checkpoint used to
 * resume an interrupted generation without redoing finished work and without ever reading a
 * half-written segment.
 */
class PcmSegmentStore(val dir: File) {

    init {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
    }

    fun file(index: Int): File = File(dir, "seg_%06d.pcm".format(index))

    fun isComplete(index: Int): Boolean = file(index).isFile

    fun completedCount(total: Int): Int = (0 until total).count { isComplete(it) }

    fun sampleCount(index: Int): Long = file(index).length() / 2

    fun write(index: Int, samples: FloatArray) {
        val target = file(index)
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            val buf = ByteBuffer.allocate(CHUNK * 2).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < samples.size) {
                buf.clear()
                val n = minOf(CHUNK, samples.size - i)
                for (k in 0 until n) buf.putShort(floatToPcm16(samples[i + k], 1f))
                out.write(buf.array(), 0, n * 2)
                i += n
            }
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Cannot finalize segment $index")
        }
    }

    /** Peak absolute amplitude (0..1) of a stored segment. */
    fun peak(index: Int): Float {
        var peak = 0
        forEachBlock(index) { block, n ->
            for (k in 0 until n) {
                val v = abs(block[k].toInt())
                if (v > peak) peak = v
            }
        }
        return peak / 32768f
    }

    /** Streams the segment in blocks; [block] is reused between calls. */
    inline fun forEachBlock(index: Int, consumer: (ShortArray, Int) -> Unit) {
        val bytes = ByteArray(CHUNK * 2)
        val shorts = ShortArray(CHUNK)
        BufferedInputStream(file(index).inputStream(), CHUNK * 2).use { input ->
            var carry = -1
            while (true) {
                var read = 0
                if (carry >= 0) { bytes[0] = carry.toByte(); read = 1; carry = -1 }
                val r = input.read(bytes, read, bytes.size - read)
                if (r < 0 && read == 0) break
                if (r > 0) read += r
                val whole = read / 2
                if (read % 2 == 1) carry = bytes[read - 1].toInt() and 0xFF
                for (k in 0 until whole) {
                    shorts[k] = ((bytes[2 * k].toInt() and 0xFF) or (bytes[2 * k + 1].toInt() shl 8)).toShort()
                }
                if (whole > 0) consumer(shorts, whole)
                if (r < 0) break
            }
        }
    }

    fun deleteAll() {
        dir.deleteRecursively()
    }

    companion object {
        const val CHUNK = 16384

        fun floatToPcm16(sample: Float, gain: Float): Short {
            val v = (sample * gain * 32767f).roundToInt()
            return v.coerceIn(-32768, 32767).toShort()
        }
    }
}
