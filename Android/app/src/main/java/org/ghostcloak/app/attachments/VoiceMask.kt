package org.ghostcloak.app.attachments

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import org.ghostcloak.attachments.AttachmentKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Local-only selection. It is never placed in a message or transport request. */
enum class VoiceMask(val label: String) {
    OFF("Original"), SUBTLE("Subtle"), STRONG("Strong"), SYNTHETIC("Synthetic")
}

/** Bounded, on-device AAC decode → PCM transform → AAC/M4A encode. */
internal object VoiceMasking {
    private const val RATE = 16_000
    private const val MAX_SAMPLES = RATE * 300
    private const val MIME = "audio/mp4a-latm"

    fun transform(input: File, output: File, preset: VoiceMask): Long {
        require(preset != VoiceMask.OFF)
        require(input.length() in 1..AttachmentKind.VOICE_NOTE.maximumBytes)
        try {
            val samples = decode(input)
            val transformed = process(samples, preset)
            encode(transformed, output)
            val duration = validate(output)
            check(decode(output).isNotEmpty())
            check(duration in 500..300_000 && output.length() in 1..AttachmentKind.VOICE_NOTE.maximumBytes)
            check(abs(duration - samples.size * 1000L / RATE) < 15_000)
            return duration
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }

    /** Resampling changes pitch. A separate resonant two-pole filter changes spectral envelope;
     * synthetic additionally applies a low-depth fixed carrier. No biometric claim is made. */
    internal fun process(source: ShortArray, preset: VoiceMask): ShortArray {
        require(source.size in 1..MAX_SAMPLES)
        if (preset == VoiceMask.OFF) return source.copyOf()
        val pitch = when (preset) {
            VoiceMask.SUBTLE -> 0.94
            VoiceMask.STRONG -> 0.82
            VoiceMask.SYNTHETIC -> 1.12
            else -> 1.0
        }
        // Granular overlap-add restores the duration after the pitch-changing resample.
        val shifted = ShortArray((source.size / pitch).roundToInt()) { i ->
            val position = i * pitch
            val left = position.toInt().coerceIn(0, source.lastIndex)
            val right = min(left + 1, source.lastIndex)
            (source[left] + (source[right] - source[left]) * (position - left)).roundToInt().toShort()
        }
        val result = ShortArray(source.size)
        val frame = 640
        val hop = 320
        val window = FloatArray(frame) { j -> (0.5 - 0.5 * cos(2.0 * PI * j / (frame - 1))).toFloat() }
        val synthesisHop = (hop * source.size.toDouble() / shifted.size).roundToInt().coerceAtLeast(1)
        val accumulator = FloatArray(source.size + frame)
        val weights = FloatArray(accumulator.size)
        var from = 0
        var to = 0
        while (from + frame < shifted.size && to + frame < accumulator.size) {
            for (j in 0 until frame) {
                val weight = window[j]
                accumulator[to + j] += shifted[from + j] * weight
                weights[to + j] += weight
            }
            from += hop
            to += synthesisHop
        }
        val resonance = when (preset) {
            VoiceMask.SUBTLE -> 0.78f
            VoiceMask.STRONG -> 0.54f
            VoiceMask.SYNTHETIC -> 0.38f
            else -> 1f
        }
        var low = 0f
        var previous = 0f
        for (i in result.indices) {
            val raw = if (weights[i] > 0.01f) accumulator[i] / weights[i] else 0f
            low += resonance * (raw - low)
            val shaped = when (preset) {
                VoiceMask.SUBTLE -> 0.78f * raw + 0.22f * low
                VoiceMask.STRONG -> 0.40f * raw + 0.75f * low - 0.15f * previous
                VoiceMask.SYNTHETIC -> (0.40f * raw + 0.60f * low) *
                    (0.80f + 0.20f * sin(2.0 * PI * 73 * i / RATE).toFloat())
                else -> raw
            }
            previous = raw
            result[i] = shaped.roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return result
    }

    private fun decode(file: File): ShortArray {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("audio_track_missing")
            val format = extractor.getTrackFormat(track)
            check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == RATE)
            check(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
            extractor.selectTrack(track)
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val bytes = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var tries = 0
            while (!outputEnded) {
                check(++tries < 100_000)
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val actual = decoder.outputFormat
                    check(actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) == RATE)
                    check(actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
                    if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        check(actual.getInteger(MediaFormat.KEY_PCM_ENCODING) == 2) // PCM 16-bit
                } else if (index >= 0) {
                    if (info.size > 0) {
                        check(bytes.size() + info.size <= MAX_SAMPLES * 2)
                        val buffer = decoder.getOutputBuffer(index)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val part = ByteArray(info.size)
                        buffer.get(part)
                        bytes.write(part)
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(index, false)
                }
            }
            val raw = bytes.toByteArray()
            check(raw.size % 2 == 0 && raw.size >= RATE)
            val pcm = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return ShortArray(pcm.remaining()).also(pcm::get)
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            decoder?.release()
            extractor.release()
        }
    }

    internal fun encode(samples: ShortArray, file: File) {
        val format = MediaFormat.createAudioFormat(MIME, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val encoder = MediaCodec.createEncoderByType(MIME)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val info = MediaCodec.BufferInfo()
            var position = 0
            var inputEnded = false
            var finished = false
            var track = -1
            var tries = 0
            while (!finished) {
                check(++tries < 100_000)
                val input = if (inputEnded) -1 else encoder.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val buffer = encoder.getInputBuffer(input)!!
                    if (position >= samples.size) {
                        encoder.queueInputBuffer(input, 0, 0, samples.size * 1_000_000L / RATE,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        val count = min(buffer.capacity() / 2, samples.size - position)
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until count) buffer.putShort(samples[position + i])
                        encoder.queueInputBuffer(input, 0, count * 2, position * 1_000_000L / RATE, 0)
                        position += count
                    }
                }
                val output = encoder.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!started)
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start(); started = true
                } else if (output >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(started)
                        muxer.writeSampleData(track, encoder.getOutputBuffer(output)!!, info)
                        check(file.length() <= AttachmentKind.VOICE_NOTE.maximumBytes)
                    }
                    finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(output, false)
                }
            }
        } finally {
            try { encoder.stop() } catch (_: Exception) {}
            encoder.release()
            if (started) muxer.stop()
            muxer.release()
        }
    }

    internal fun validate(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            check(extractor.trackCount == 1)
            val format = extractor.getTrackFormat(0)
            check(format.getString(MediaFormat.KEY_MIME) == MIME)
            check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == RATE)
            check(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
            extractor.selectTrack(0)
            check(extractor.readSampleData(ByteBuffer.allocate(8192), 0) > 0)
            return format.getLong(MediaFormat.KEY_DURATION) / 1000
        } finally { extractor.release() }
    }
}
