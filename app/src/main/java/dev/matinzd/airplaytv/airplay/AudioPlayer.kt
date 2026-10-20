package dev.matinzd.airplaytv.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow

/**
 * Plays AirPlay audio through an AudioTrack. ALAC/PCM arrive already decoded;
 * AAC (AAC-ELD while mirroring) is decoded with MediaCodec.
 *
 * Every packet is played at `sender timestamp + sync latency` (see [AvSync]):
 * if a packet would be heard too early, silence is inserted first; if it would
 * be heard too late, it's dropped. That keeps audio on the same timeline as the
 * video and absorbs clock drift between the iPhone and the TV.
 *
 * Everything runs on one audio thread so the network threads never block.
 */
class AudioPlayer(private val sync: AvSync) {
    private val thread = HandlerThread("airplay-audio", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)
    private val queued = AtomicInteger()

    private var track: AudioTrack? = null
    private var lowLatencyTrack = false
    private var lateRun = 0
    private var aac: MediaCodec? = null
    private var aacPtsUs = 0L
    private var samplesPerFrame = 1024
    private var gain = 1f
    private val info = MediaCodec.BufferInfo()
    private var pcmScratch = ByteArray(0)

    // Where the AudioTrack is: frames written so far, and its output pipeline latency.
    private var framesWritten = 0L
    private var pipelineNs = -1L
    private val timestamp = AudioTimestamp()
    // Audio held in the track before playback starts (its start threshold).
    private var startBufferNs = 0L
    private val stats = Stats()

    fun configure(ct: Int, samplesPerFrame: Int, lowLatency: Boolean) = post {
        releaseLocked()
        this.samplesPerFrame = if (samplesPerFrame > 0) samplesPerFrame else 1024
        lowLatencyTrack = lowLatency
        track = createTrack(lowLatency)
        if (ct == AirPlayNative.AUDIO_AAC_ELD || ct == AirPlayNative.AUDIO_AAC_LC) {
            aac = createAacDecoder(eld = ct == AirPlayNative.AUDIO_AAC_ELD)
        }
    }

    /** [ptsNs]: sender timestamp on the System.nanoTime() clock, or 0 if unknown. */
    fun playPcm(data: ByteArray, ptsNs: Long) {
        val arrivalNs = System.nanoTime()
        postData { writeSynced(data, 0, data.size, ptsNs, arrivalNs) }
    }

    fun playAac(data: ByteArray, ptsNs: Long) {
        val arrivalNs = System.nanoTime()
        postData { decodeAac(data, ptsNs, arrivalNs) }
    }

    fun flush() = post {
        track?.apply { pause(); flush(); play() }
        runCatching { aac?.flush() }
        aacPtsUs = 0
        framesWritten = 0
            }

    fun setVolume(gain: Float) = post {
        this.gain = gain
        track?.setVolume(gain)
    }

    fun stop() = post { releaseLocked() }

    fun release() {
        stop()
        thread.quitSafely()
    }

    private fun post(block: () -> Unit) {
        handler.post(block)
    }

    /** Drops data instead of building up latency if the audio thread falls behind. */
    private fun postData(block: () -> Unit) {
        if (queued.get() > MAX_QUEUED) return
        queued.incrementAndGet()
        handler.post {
            queued.decrementAndGet()
            block()
        }
    }

    private fun writeSynced(pcm: ByteArray, offset: Int, length: Int, ptsNs: Long, arrivalNs: Long) {
        val t = track ?: return
        val frames = length / BYTES_PER_FRAME
        if (ptsNs > 0) {
            val now = System.nanoTime()
            if (!t.getTimestamp(timestamp)) stats.noTimestamp++
            val heardAtNs = nextFrameHeardAtNs(t, now)
            if (heardAtNs != null) {
                // What audio needs: how late it arrived + the device's output latency + the
                // track's start buffer. Deliberately nothing that depends on what's queued:
                // the queue holds the silence we insert to delay audio, and counting it fed
                // back into the latency (it ran away twice).
                val deviceNs = if (pipelineNs >= 0) pipelineNs else DEFAULT_DEVICE_LATENCY_NS
                val needNs = (arrivalNs - ptsNs) + deviceNs + startBufferNs
                sync.reportAudio(needNs)
                val error = heardAtNs - (ptsNs + sync.audioLatencyNs) // > 0: late, < 0: early
                stats.error(error)
                // Inserted silence only grows the queue and drops never shrink it, so a jittery
                // sender (iPhone) can settle between MAX_LATE and RESYNC_LATE and lose every
                // packet. Start over after a run of drops, as for a far-late packet.
                val stuckLate = error > MAX_LATE_NS && lateRun >= RESYNC_AFTER_DROPS
                lateRun = if (error > MAX_LATE_NS) lateRun + 1 else 0
                when {
                    (error > RESYNC_LATE_NS || stuckLate) && queuedFrames(t) > SAMPLE_RATE / 20 -> {
                        lateRun = 0
                        // Far behind with audio queued (e.g. for a higher latency): start over.
                        // Once the queue is empty, further late packets are simply dropped,
                        // so a backlog of stale packets doesn't trigger a resync each.
                        Log.i(TAG, "audio ${error / AvSync.MS} ms late, resyncing")
                        resetTrack(t)
                        return
                    }
                    error > (if (lowLatencyTrack) MAX_LATE_NS else MAX_LATE_MUSIC_NS) -> {
                        stats.dropped++
                        return
                    }
                    error < -BOGUS_EARLY_NS -> {
                        // Timestamps this far off are wrong (the native layer re-anchors);
                        // play the packet rather than fill the track with silence.
                        stats.bogus++
                    }
                    error < -MAX_EARLY_NS -> {
                        val silence = ((-error) * SAMPLE_RATE / 1_000_000_000L).toInt()
                            .coerceAtMost(SAMPLE_RATE) // at most 1 s per packet
                        if (!writeSilence(t, silence)) return
                        stats.silenceFrames += silence
                    }
                }
            }
        }
        if (!checkWrite(t, t.write(pcm, offset, length))) return
        framesWritten += frames
        stats.maybeLog()
    }

    /** A negative write result means the track is unusable (e.g. audio output changed). */
    private fun checkWrite(t: AudioTrack, result: Int): Boolean {
        if (result >= 0) return true
        Log.w(TAG, "AudioTrack write failed ($result, state ${t.playState}); recreating it")
        runCatching { t.release() }
        track = createTrack(lowLatencyTrack)
        return false
    }

    private fun queuedFrames(t: AudioTrack) = framesWritten - t.playbackHeadPosition.toLong()

    private fun resetTrack(t: AudioTrack) {
        t.pause()
        t.flush()
        t.play()
        framesWritten = 0
            }

    /**
     * When the next frame written now would be heard, from the track's latest
     * timestamp. Also tracks the device's own output latency: that time minus
     * what's still queued in the track, smoothed.
     */
    private fun nextFrameHeardAtNs(t: AudioTrack, now: Long): Long? {
        val queuedNs = (framesWritten - t.playbackHeadPosition.toLong()).coerceAtLeast(0) *
            1_000_000_000L / SAMPLE_RATE
        if (!t.getTimestamp(timestamp) || timestamp.framePosition <= 0) {
            // A new track takes ~1 s to report timestamps. Estimate meanwhile from what's
            // queued plus the device latency, instead of writing without timing (which
            // overfills the queue and forces a resync once timestamps arrive).
            return now + queuedNs + (if (pipelineNs >= 0) pipelineNs else DEFAULT_DEVICE_LATENCY_NS)
        }
        val framesAhead = framesWritten - timestamp.framePosition
        val heardAt = timestamp.nanoTime + framesAhead * 1_000_000_000L / SAMPLE_RATE
        val deviceNs = (heardAt - now - queuedNs).coerceAtLeast(0)
        if (pipelineNs < 0) {
            pipelineNs = deviceNs
            Log.i(TAG, "audio device latency ${deviceNs / AvSync.MS} ms")
        } else {
            pipelineNs += (deviceNs - pipelineNs) / 16
        }
        return heardAt
    }

    private fun writeSilence(t: AudioTrack, frames: Int): Boolean {
        var remaining = frames * BYTES_PER_FRAME
        val zeros = ByteArray(minOf(remaining, 16 * 1024))
        while (remaining > 0) {
            val n = minOf(remaining, zeros.size)
            val written = t.write(zeros, 0, n)
            if (!checkWrite(t, written)) return false
            remaining -= written
            framesWritten += written / BYTES_PER_FRAME
        }
        return true
    }

    private fun createTrack(lowLatency: Boolean): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        // Room to hold audio until its presentation time without ever blocking the audio
        // thread (a blocked thread made packets queue up and look late). The buffer size
        // doesn't add latency: what's actually queued does.
        val bufferBytes = maxOf(minBuffer * 2, SAMPLE_RATE * BYTES_PER_FRAME * 3)
        framesWritten = 0
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                // A streaming track only starts once its buffer is full, unless told
                // otherwise: with a 3 s buffer that held music back for 3 s (and gave no
                // timestamps meanwhile). What's queued when it starts stays queued, so this
                // is also the audio latency floor: 40 ms while mirroring, one device
                // buffer for music.
                if (Build.VERSION.SDK_INT >= 31) {
                    val frames = if (lowLatency) SAMPLE_RATE / 25 else minBuffer / BYTES_PER_FRAME
                    it.setStartThresholdInFrames(frames)
                    startBufferNs = frames * 1_000_000_000L / SAMPLE_RATE
                }
                it.setVolume(gain)
                it.play()
            }
    }

    private fun createAacDecoder(eld: Boolean): MediaCodec? {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
            if (eld) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
                setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0xf8.toByte(), 0xe8.toByte(), 0x50, 0x00)))
            } else {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x12, 0x10)))
            }
        }
        // Prefer the platform software decoder: hardware AAC decoders often lack AAC-ELD.
        for (create in listOf(
            { MediaCodec.createByCodecName("c2.android.aac.decoder") },
            { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) },
        )) {
            try {
                return create().apply {
                    configure(format, null, null, 0)
                    start()
                    Log.i(TAG, "AAC decoder $name (eld=$eld)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "AAC decoder unavailable: ${e.message}")
            }
        }
        return null
    }

    private fun decodeAac(data: ByteArray, ptsNs: Long, arrivalNs: Long) {
        val codec = aac ?: return
        try {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                codec.getInputBuffer(inIndex)?.apply { clear(); put(data) }
                // The sender timestamp travels through the decoder; fall back to a counter.
                val ptsUs = if (ptsNs > 0) ptsNs / 1000 else aacPtsUs
                codec.queueInputBuffer(inIndex, 0, data.size, ptsUs, 0)
                aacPtsUs = ptsUs + samplesPerFrame * 1_000_000L / SAMPLE_RATE
            }
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(info, 0)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (outIndex < 0) break
                val out = codec.getOutputBuffer(outIndex)
                if (out != null && info.size > 0) {
                    if (pcmScratch.size < info.size) pcmScratch = ByteArray(info.size)
                    out.position(info.offset)
                    out.get(pcmScratch, 0, info.size)
                    writeSynced(pcmScratch, 0, info.size, if (ptsNs > 0) info.presentationTimeUs * 1000 else 0, arrivalNs)
                }
                codec.releaseOutputBuffer(outIndex, false)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AAC decode failed", e)
        }
    }

    private fun releaseLocked() {
        aac?.let {
            runCatching { it.stop() }
            it.release()
        }
        aac = null
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
        aacPtsUs = 0
    }

    /** Logs, every 5 s, how far audio was from its slot and what the track is doing. */
    private inner class Stats {
        var dropped = 0
        var silenceFrames = 0L
        var noTimestamp = 0
        var bogus = 0
        private var count = 0
        private var errorSumNs = 0L
        private var windowStart = System.nanoTime()

        fun error(errorNs: Long) {
            count++
            errorSumNs += errorNs
            maybeLog()
        }

        fun maybeLog() {
            val elapsed = System.nanoTime() - windowStart
            if (elapsed < 5_000_000_000L || !Diagnostics.enabled) return
            val t = track
            val queuedMs = if (t != null) (framesWritten - t.playbackHeadPosition.toLong()) * 1000 / SAMPLE_RATE else 0
            Log.i(TAG, ("sync error avg %d ms, dropped %d, silence %d ms, no-timestamp %d, bogus %d, " +
                "queued %d ms, device latency %d ms, state %d, backlog %d").format(
                if (count > 0) errorSumNs / count / AvSync.MS else 0, dropped,
                silenceFrames * 1000 / SAMPLE_RATE, noTimestamp, bogus, queuedMs, pipelineNs / AvSync.MS,
                t?.playState ?: -1, queued.get()))
            windowStart = System.nanoTime()
            count = 0; errorSumNs = 0; dropped = 0; silenceFrames = 0; noTimestamp = 0; bogus = 0
        }
    }

    companion object {
        /** AirPlay sends -30..0 dB; -30 and below (and -144) mean mute. */
        fun airPlayGain(db: Float): Float = if (db <= -30f) 0f else 10f.pow(db / 20f)

        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 44100
        private const val BYTES_PER_FRAME = 4
        private const val MAX_QUEUED = 400
        // Audio up to 45 ms behind the picture isn't noticeable (ITU-R BT.1359);
        // the track's own buffering keeps it ~20 ms behind, so don't drop before that.
        private const val MAX_LATE_NS = 45 * AvSync.MS
        // Music has no picture to stay in sync with; it sits ~40 ms behind its slot here.
        private const val MAX_LATE_MUSIC_NS = 120 * AvSync.MS
        private const val RESYNC_LATE_NS = 150 * AvSync.MS
        private const val RESYNC_AFTER_DROPS = 10 // about 110 ms of AAC-ELD packets
        private const val DEFAULT_DEVICE_LATENCY_NS = 200 * AvSync.MS // Streamer HDMI: ~210 ms
        private const val MAX_EARLY_NS = 15 * AvSync.MS
        private const val BOGUS_EARLY_NS = 4_000 * AvSync.MS
    }
}
