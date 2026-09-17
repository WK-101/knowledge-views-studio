package com.cairn.reader.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Decodes a compressed audio source (a podcast enclosure, a direct media URL, or a local file) to
 * 16 kHz mono 16-bit PCM — the format on-device speech engines want — and streams it out in chunks
 * so an hour-long episode never has to sit in memory at once. Pure platform APIs (MediaExtractor +
 * MediaCodec), no native code of our own; [MediaExtractor.setDataSource] reads http(s) or file paths.
 */
object AudioDecoder {

    private const val TARGET_RATE = 16_000

    /**
     * Decode [source] and emit little-endian 16-bit mono PCM at 16 kHz via [onPcm] (buffer + valid
     * length in bytes). [onProgress] reports 0f..1f by presentation time. [isCancelled] is polled so
     * a long decode can be stopped. Returns true if any audio was produced.
     */
    fun decodeTo16kMonoPcm(
        source: String,
        onPcm: (ByteArray, Int) -> Unit,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var produced = false
        try {
            extractor.setDataSource(source)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return false
            val srcRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = runCatching { inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
            val durationUs = runCatching { inputFormat.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            // Streaming linear resampler state: fractional read position into the source stream and the
            // last source sample carried across output buffers so chunk seams stay continuous.
            var resamplePos = 0.0
            var lastSample = 0.0f
            val ratio = srcRate.toDouble() / TARGET_RATE

            while (!sawOutputEos && !isCancelled()) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val mono16k = resampleChunk(outBuf, channels, ratio,
                            posRef = { resamplePos }, setPos = { resamplePos = it },
                            lastRef = { lastSample }, setLast = { lastSample = it })
                        if (mono16k.second > 0) { onPcm(mono16k.first, mono16k.second); produced = true }
                        if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
            onProgress(1f)
            return produced
        } catch (_: Exception) {
            return produced
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Downmix interleaved 16-bit PCM to mono and linear-resample to 16 kHz. Returns (bytes, length). */
    private fun resampleChunk(
        buf: ByteBuffer,
        channels: Int,
        ratio: Double,
        posRef: () -> Double,
        setPos: (Double) -> Unit,
        lastRef: () -> Float,
        setLast: (Float) -> Unit,
    ): Pair<ByteArray, Int> {
        val shorts = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frameCount = shorts.remaining() / channels.coerceAtLeast(1)
        if (frameCount <= 0) return ByteArray(0) to 0
        // Downmix to a mono float array for this buffer.
        val mono = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            var sum = 0
            for (c in 0 until channels) sum += shorts.get(f * channels + c).toInt()
            mono[f] = sum.toFloat() / channels
        }
        // Resample from the carried fractional position; index -1 maps to the carried last sample.
        val out = ArrayList<Short>(((frameCount / ratio) + 2).toInt().coerceAtLeast(1))
        var pos = posRef()
        while (pos < frameCount) {
            val i = Math.floor(pos).toInt()
            val frac = (pos - i).toFloat()
            val a = if (i < 0) lastRef() else mono[i.coerceAtMost(frameCount - 1)]
            val b = mono[(i + 1).coerceIn(0, frameCount - 1)]
            val s = (a + (b - a) * frac).toInt().coerceIn(-32768, 32767)
            out.add(s.toShort())
            pos += ratio
        }
        setPos(pos - frameCount) // carry the fractional remainder into the next buffer
        setLast(mono[frameCount - 1])
        val bytes = ByteArray(out.size * 2)
        for (i in out.indices) {
            val v = out[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes to bytes.size
    }
}
