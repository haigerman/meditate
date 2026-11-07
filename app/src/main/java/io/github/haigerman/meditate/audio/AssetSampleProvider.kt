package io.github.haigerman.meditate.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Lädt Aufnahmen aus `assets/sounds/<name>.mp3` im Hintergrund, dekodiert sie mit
 * dem System-Decoder und hält sie als PCM im Speicher.
 */
class AssetSampleProvider(context: Context, private val targetRate: Int) : SampleProvider {
    private val appContext = context.applicationContext
    private val cache = ConcurrentHashMap<String, Pcm>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    override fun get(name: String): Pcm? {
        cache[name]?.let { return it }
        if (loading.add(name)) {
            Thread({
                try {
                    cache[name] = decode("sounds/$name.mp3").resampled(targetRate)
                } catch (e: Exception) {
                    Log.e("Meditate", "Aufnahme $name konnte nicht geladen werden", e)
                }
            }, "decode-$name").start()
        }
        return null
    }

    private fun decode(path: String): Pcm {
        val afd = appContext.assets.openFd(path)
        val extractor = MediaExtractor()
        extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var out = ShortArray(sampleRate * 2 * 60)
        var size = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val shorts = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val n = shorts.remaining()
                    // in Stereo umwandeln
                    val needed = if (channels == 1) n * 2 else n / channels * 2
                    if (size + needed > out.size) out = out.copyOf(maxOf(out.size * 2, size + needed))
                    if (channels == 1) {
                        for (k in 0 until n) {
                            val s = shorts.get()
                            out[size++] = s
                            out[size++] = s
                        }
                    } else {
                        for (k in 0 until n / channels) {
                            out[size++] = shorts.get()
                            out[size++] = shorts.get()
                            repeat(channels - 2) { shorts.get() }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        codec.stop()
        codec.release()
        extractor.release()
        return Pcm(out.copyOf(size), sampleRate)
    }
}
