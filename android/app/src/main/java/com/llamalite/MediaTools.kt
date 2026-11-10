package com.llamalite

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Multimedialne nastroje BEZ ffmpeg:
 *  - audio (mp3/m4a/ogg/wav/flac...) -> 16 kHz mono WAV  (MediaExtractor + MediaCodec + resample)
 *  - video -> N snimok (jpg) cez MediaMetadataRetriever + zvukova stopa -> WAV
 *  - obrazy/text prejdu priamo.
 */
object MediaTools {

    private val IMG_EXT = setOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")
    private val AUD_EXT = setOf(".wav", ".mp3", ".ogg", ".opus", ".flac", ".m4a", ".aac", ".amr", ".aiff")
    private val VID_EXT = setOf(".mp4", ".mkv", ".mov", ".webm", ".avi", ".m4v", ".3gp", ".ts")
    private val TXT_EXT = setOf(
        ".txt", ".md", ".markdown", ".csv", ".tsv", ".json", ".jsonl", ".log", ".xml", ".yaml", ".yml",
        ".py", ".js", ".ts", ".kt", ".java", ".c", ".cpp", ".h", ".css", ".html", ".sh", ".ini", ".conf",
        ".toml", ".sql", ".srt", ".vtt", ".rst", ".tex"
    )
    const val TXT_MAX = 200_000

    fun uploadsDir(ctx: Context): File = File(ctx.filesDir, "uploads").apply { mkdirs() }

    fun classify(name: String): String {
        val e = name.substringAfterLast('.', "").lowercase().let { if (it.isEmpty()) "" else ".$it" }
        return when {
            IMG_EXT.contains(e) -> "image"
            AUD_EXT.contains(e) -> "audio"
            VID_EXT.contains(e) -> "video"
            TXT_EXT.contains(e) -> "text"
            else -> "other"
        }
    }

    fun saveUpload(ctx: Context, name: String, data: ByteArray): File {
        val safe = name.substringAfterLast('/').map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
        val base = safe.ifEmpty { "upload.bin" }
        var f = File(uploadsDir(ctx), base)
        var i = 2
        while (f.exists()) {
            val stem = base.substringBeforeLast('.', base)
            val ext = if (base.contains('.')) "." + base.substringAfterLast('.') else ""
            f = File(uploadsDir(ctx), "${stem}_$i$ext"); i++
        }
        f.writeBytes(data)
        return f
    }

    // ---------- AUDIO -> WAV 16k mono ----------
    /** Dekoduje audio stopu suboru na 16 kHz mono 16-bit WAV. Vrati null ak zlyha. */
    fun toWav16k(input: File): File? {
        return try {
            val pcm = decodeToPcm(input) ?: return null
            val wav = File(input.parentFile, input.name.substringBeforeLast('.') + "_16k.wav")
            writeWav(wav, pcm.first, pcm.second)  // (short[] mono16k, sampleRate=16000)
            wav
        } catch (e: Exception) { null }
    }

    /** Vrati Pair<int 16k mono>, 16000). */
    private fun decodeToPcm(input: File): Pair<ShortArray, Int>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return null
            extractor.selectTrack(track)

            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val raw = ArrayList<Short>()
            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            val timeout = 10_000L
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(timeout)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, timeout)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(outIdx)!!
                        out.position(info.offset); out.limit(info.offset + info.size)
                        val bb = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (bb.hasRemaining()) raw.add(bb.get())
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                }
            }
            codec.stop(); codec.release()

            // -> mono
            val ch = if (srcCh < 1) 1 else srcCh
            val mono = ShortArray(raw.size / ch)
            for (i in mono.indices) {
                var s = 0
                for (c in 0 until ch) s += raw[i * ch + c]
                mono[i] = (s / ch).toShort()
            }
            // -> 16 kHz (linear)
            val outLen = (mono.size.toLong() * 16000 / srcRate).toInt()
            val out = ShortArray(outLen)
            for (i in 0 until outLen) {
                val src = i.toDouble() * srcRate / 16000.0
                val i0 = src.toInt().coerceIn(0, mono.size - 1)
                val i1 = (i0 + 1).coerceAtMost(mono.size - 1)
                val fr = src - i0
                out[i] = (mono[i0] * (1 - fr) + mono[i1] * fr).toInt().toShort()
            }
            return Pair(out, 16000)
        } catch (e: Exception) {
            null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun writeWav(f: File, samples: ShortArray, rate: Int) {
        val dataLen = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataLen); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(rate); buf.putInt(rate * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(dataLen)
        for (s in samples) buf.putShort(s)
        FileOutputStream(f).use { it.write(buf.array()) }
    }

    // ---------- VIDEO -> snimky + audio ----------
    data class VideoResult(val frames: List<File>, val audio: File?)

    fun videoExtract(input: File, maxFrames: Int = 6, withAudio: Boolean = true): VideoResult {
        val frames = ArrayList<File>()
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(input.absolutePath)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val n = maxFrames.coerceAtLeast(1)
            for (i in 0 until n) {
                val tUs = if (durMs > 0) (durMs * 1000L * i / n) else (i * 1_000_000L)
                val bmp = mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                val scaled = scaleDown(bmp, 768)
                val out = File(input.parentFile, input.name.substringBeforeLast('.') + "_f%02d.jpg".format(i + 1))
                FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                if (scaled !== bmp) scaled.recycle()
                bmp.recycle()
                frames.add(out)
            }
            mmr.release()
        } catch (_: Exception) {}
        val audio = if (withAudio) toWav16k(input) else null
        return VideoResult(frames, audio)
    }

    private fun scaleDown(bmp: Bitmap, targetW: Int): Bitmap {
        if (bmp.width <= targetW) return bmp
        val h = (bmp.height.toDouble() * targetW / bmp.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, targetW, h, true)
    }

    fun readText(f: File): Pair<String, Boolean> {
        return try {
            var txt = f.readText(Charsets.UTF_8)
            val trunc = txt.length > TXT_MAX
            if (trunc) txt = txt.substring(0, TXT_MAX)
            Pair(txt, trunc)
        } catch (e: Exception) { Pair("", false) }
    }
}