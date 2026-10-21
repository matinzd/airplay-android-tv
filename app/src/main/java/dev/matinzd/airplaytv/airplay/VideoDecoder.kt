package dev.matinzd.airplaytv.airplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 / H.265 mirror stream -> MediaCodec -> Surface, on the shared [AvSync] timeline.
 *
 * Frames wait here, still compressed, until just before their slot
 * (`timestamp + sync latency - decode time`), then are decoded and shown at the
 * slot. Waiting before the decoder rather than after it matters: the display
 * queue only has a few buffers, and holding decoded frames there stalls the codec.
 * In low-latency mode ([AvSync.immediateVideo]) nothing waits: every frame is
 * decoded on arrival and shown as soon as it is decoded.
 *
 * Frames start arriving before the playback screen (and its Surface) exists,
 * especially when the TV is waking from standby, and the iPhone only sends a
 * keyframe at the start and on rotation. So every frame since the last
 * keyframe is kept and replayed into the decoder once a Surface is attached.
 */
class VideoDecoder(
    private val sync: AvSync,
    private val onVideoSize: (width: Int, height: Int) -> Unit,
) {
    private val lock = Any()
    private val thread = HandlerThread("video-decoder").apply { start() }
    private val handler = Handler(thread.looper)

    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var hevc = false
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var errorRetries = 0
    private var lastStartFailureNs = 0L
    private val loggedDecoders = mutableSetOf<String>()

    private class Frame(val data: ByteArray, val ptsNs: Long, val arrivalNs: Long)

    /** Frames inside the decoder, by pts (us): arrival time and when they were queued. */
    private val inFlight = LinkedHashMap<Long, LongArray>()
    @Volatile
    private var decodeLeadNs = 30 * AvSync.MS
    @Volatile
    private var codecStartedAtNs = 0L
    private var feedScheduledAtNs = 0L
    private val feedTask = Runnable {
        synchronized(lock) {
            feedScheduledAtNs = 0
            codec?.let(::feedLocked)
        }
    }

    private val gop = ArrayList<Frame>()          // frames since the last keyframe
    private var gopBytes = 0L
    private var gopOverflow = false
    private val pending = ArrayDeque<Frame>()     // waiting for their decode time / an input buffer
    private val stats = Stats()
    private val freeInputs = ArrayDeque<Int>()

    /** [ptsNs]: sender timestamp on the System.nanoTime() clock, or 0 if unknown. */
    fun onFrame(data: ByteArray, ptsNs: Long) {
        val frame = Frame(data, ptsNs, System.nanoTime())
        var toRelease: MediaCodec? = null
        synchronized(lock) {
            var keyframe = false
            for (nal in VideoNal.leadingUnits(data, hevc)) {
                when (nal.kind) {
                    VideoNal.Kind.PARAM_VPS -> vps = data.copyOfRange(nal.start, nal.end)
                    VideoNal.Kind.PARAM_SPS -> {
                        val newSps = data.copyOfRange(nal.start, nal.end)
                        if (!newSps.contentEquals(sps)) {
                            // New resolution (rotation) or new stream: start a fresh decoder.
                            sps = newSps
                            toRelease = detachCodecLocked()
                        }
                    }
                    VideoNal.Kind.PARAM_PPS -> pps = data.copyOfRange(nal.start, nal.end)
                    VideoNal.Kind.KEYFRAME -> keyframe = true
                    else -> Unit
                }
            }
            stats.received()
            if (keyframe) {
                gop.clear()
                gopBytes = 0
                gopOverflow = false
            }
            val decodable = keyframe || gop.isNotEmpty() || (codec != null && gopOverflow)
            if (!decodable) return@synchronized

            if (!gopOverflow) {
                if (gopBytes + data.size > MAX_GOP_BYTES) {
                    // Too long to cache; keep decoding live, cache again from the next keyframe.
                    gop.clear()
                    gopBytes = 0
                    gopOverflow = true
                } else {
                    gop += frame
                    gopBytes += data.size
                }
            }

            val c = codec
            if (c != null) {
                pending.addLast(frame)
                feedLocked(c)
            } else if (toRelease == null) {
                startCodecLocked()
            }
        }
        // A Surface can only feed one decoder: release the old one before starting the next.
        toRelease?.let {
            release(it)
            synchronized(lock) { if (codec == null) startCodecLocked() }
        }
    }

    fun setSurface(newSurface: Surface?) {
        var toRelease: MediaCodec? = null
        synchronized(lock) {
            if (newSurface === surface) return
            toRelease = detachCodecLocked()
            surface = newSurface
            errorRetries = 0
            lastStartFailureNs = 0
            if (toRelease == null) startCodecLocked()
        }
        toRelease?.let {
            release(it)
            synchronized(lock) { if (codec == null) startCodecLocked() }
        }
    }

    /** Codec of the next mirror stream, announced before its first frame. */
    fun setCodec(isHevc: Boolean) {
        var toRelease: MediaCodec? = null
        synchronized(lock) {
            if (isHevc == hevc) return
            Log.i(TAG, "mirror codec: ${if (isHevc) "H.265" else "H.264"}")
            toRelease = detachCodecLocked()
            hevc = isHevc
            vps = null; sps = null; pps = null
            gop.clear(); gopBytes = 0; gopOverflow = false
        }
        toRelease?.let(::release)
    }

    /** The mirror stream ended. */
    fun reset() {
        var toRelease: MediaCodec? = null
        synchronized(lock) {
            toRelease = detachCodecLocked()
            vps = null
            sps = null
            pps = null
            gop.clear()
            gopBytes = 0
            gopOverflow = false
        }
        toRelease?.let(::release)
    }

    fun release() {
        reset()
        thread.quitSafely()
    }

    private fun startCodecLocked() {
        val s = surface ?: return
        val spsBytes = sps ?: return
        val ppsBytes = pps ?: return
        if (gop.isEmpty()) return
        // After a failure (e.g. the Surface is still attached to the video player), don't
        // retry on every incoming frame.
        if (System.nanoTime() - lastStartFailureNs < START_RETRY_DELAY_NS) return
        var c: MediaCodec? = null
        try {
            val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, 3840, 2160).apply {
                if (hevc) {
                    // HEVC takes VPS + SPS + PPS together as csd-0.
                    val vpsBytes = vps ?: return
                    setByteBuffer("csd-0", ByteBuffer.wrap(vpsBytes + spsBytes + ppsBytes))
                } else {
                    setByteBuffer("csd-0", ByteBuffer.wrap(spsBytes))
                    setByteBuffer("csd-1", ByteBuffer.wrap(ppsBytes))
                }
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            c = createLowLatencyDecoder(mime)
            c.setCallback(Callback(), handler)
            c.configure(format, s, null, 0)
            c.start()
            codec = c
            codecStartedAtNs = System.nanoTime()
            freeInputs.clear()
            pending.clear()
            pending.addAll(gop)
            Log.i(TAG, "decoder ${c.name} started, replaying ${gop.size} frames")
        } catch (e: Exception) {
            Log.e(TAG, "failed to start decoder", e)
            c?.release()
            lastStartFailureNs = System.nanoTime()
        }
    }

    private fun createLowLatencyDecoder(mime: String): MediaCodec {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
        val lowLatency = candidates.firstOrNull {
            it.getCapabilitiesForType(mime)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        }
        if (mime !in loggedDecoders) Log.i(TAG, "$mime decoders: " + candidates.joinToString {
            val ll = it.getCapabilitiesForType(mime)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            "${it.name}${if (ll) " (low-latency)" else ""}"
        })
        loggedDecoders += mime
        return if (lowLatency != null) MediaCodec.createByCodecName(lowLatency.name)
        else MediaCodec.createDecoderByType(mime)
    }

    private fun feedLocked(c: MediaCodec) {
        while (freeInputs.isNotEmpty() && pending.isNotEmpty()) {
            val frame = pending.first()
            val now = System.nanoTime()
            // Low-latency mode: decode at once; the frame is shown as soon as it comes out.
            if (frame.ptsNs > 0 && !sync.immediateVideo) {
                val dueNs = frame.ptsNs + sync.latencyNs - decodeLeadNs
                if (dueNs - now > AvSync.MS) {
                    scheduleFeedLocked(dueNs)
                    return
                }
            }
            pending.removeFirst()
            val index = freeInputs.removeFirst()
            try {
                val buffer = c.getInputBuffer(index) ?: continue
                buffer.clear()
                if (frame.data.size > buffer.remaining()) {
                    Log.w(TAG, "frame of ${frame.data.size} bytes too large, dropped")
                    c.queueInputBuffer(index, 0, 0, 0, 0)
                    continue
                }
                buffer.put(frame.data)
                // The sender timestamp travels through the decoder as the pts.
                val ptsUs = frame.ptsNs / 1000
                inFlight[ptsUs] = longArrayOf(frame.arrivalNs, now)
                if (inFlight.size > 64) inFlight.remove(inFlight.keys.first())
                c.queueInputBuffer(index, 0, frame.data.size, ptsUs, 0)
                stats.queued(pending.size)
            } catch (e: IllegalStateException) {
                return // codec is being released
            }
        }
    }

    private fun scheduleFeedLocked(dueNs: Long) {
        if (feedScheduledAtNs != 0L && feedScheduledAtNs <= dueNs) return
        handler.removeCallbacks(feedTask)
        feedScheduledAtNs = dueNs
        handler.postDelayed(feedTask, ((dueNs - System.nanoTime()) / AvSync.MS).coerceAtLeast(1))
    }

    private fun detachCodecLocked(): MediaCodec? {
        val c = codec
        codec = null
        freeInputs.clear()
        pending.clear()
        inFlight.clear()
        handler.removeCallbacks(feedTask)
        feedScheduledAtNs = 0
        return c
    }

    private fun release(c: MediaCodec) {
        try {
            c.stop()
        } catch (_: Exception) {
        }
        c.release()
    }

    private inner class Callback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            synchronized(lock) {
                if (c !== codec) return
                freeInputs.addLast(index)
                feedLocked(c)
            }
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val now = System.nanoTime()
            val ptsNs = info.presentationTimeUs * 1000
            var renderAtNs = 0L
            if (ptsNs > 0) {
                val times = synchronized(lock) { inFlight.remove(info.presentationTimeUs) }
                if (times != null) {
                    val decodeNs = now - times[1]
                    // Track a high-water mark of decode time: decode that much ahead of the slot.
                    decodeLeadNs = if (decodeNs > decodeLeadNs) decodeNs
                    else decodeLeadNs - (decodeLeadNs - decodeNs) / 32
                    // What this stream needs: how late it arrived, plus decoding. Skip the
                    // first second after the decoder starts: those are frames cached while
                    // the playback screen was coming up (or a backlog behind them), late by
                    // design rather than because of the network.
                    val requiredNs = times[0] - ptsNs + decodeNs
                    if (now - codecStartedAtNs > STARTUP_GRACE_NS) sync.reportVideo(requiredNs)
                    val target = ptsNs + sync.latencyNs
                    if (!sync.immediateVideo && target - now in MIN_SCHEDULE_NS..MAX_SCHEDULE_NS) renderAtNs = target
                    stats.rendered(requiredNs, if (renderAtNs != 0L) renderAtNs - now else 0L, decodeLeadNs)
                }
            }
            try {
                if (renderAtNs != 0L) c.releaseOutputBuffer(index, renderAtNs) else c.releaseOutputBuffer(index, true)
            } catch (_: IllegalStateException) {
            }
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            val width = if (format.containsKey("crop-right")) {
                format.getInteger("crop-right") - format.getInteger("crop-left") + 1
            } else format.getInteger(MediaFormat.KEY_WIDTH)
            val height = if (format.containsKey("crop-bottom")) {
                format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
            } else format.getInteger(MediaFormat.KEY_HEIGHT)
            Log.i(TAG, "video size ${width}x$height")
            onVideoSize(width, height)
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "decoder error", e)
            var toRelease: MediaCodec? = null
            synchronized(lock) {
                if (c !== codec) return
                toRelease = detachCodecLocked()
            }
            toRelease?.let {
                release(it)
                synchronized(lock) { if (codec == null && errorRetries++ < 3) startCodecLocked() }
            }
        }
    }

    /** Logs throughput, how late frames arrive, and how long they wait for their slot. */
    private class Stats {
        private var windowStart = System.nanoTime()
        private var received = 0
        private var rendered = 0
        private var maxBacklog = 0
        private var lateSumNs = 0L
        private var lateMaxNs = 0L
        private var holdSumNs = 0L

        @Synchronized fun received() { received++ }
        @Synchronized fun queued(backlog: Int) { if (backlog > maxBacklog) maxBacklog = backlog }

        /** [lateNs]: latency this frame needed (arrival + decode); [holdNs]: held this long after decoding. */
        @Synchronized fun rendered(lateNs: Long, holdNs: Long, decodeLeadNs: Long) {
            rendered++
            lateSumNs += lateNs
            if (lateNs > lateMaxNs) lateMaxNs = lateNs
            holdSumNs += holdNs
            val elapsed = System.nanoTime() - windowStart
            if (elapsed >= 5_000_000_000L && Diagnostics.enabled) {
                val secs = elapsed / 1e9
                Log.i(TAG, "in %.1f fps, out %.1f fps, needed avg %d / max %d ms, held avg %d ms, backlog max %d, decode lead %d ms".format(
                    received / secs, rendered / secs, lateSumNs / rendered / 1_000_000, lateMaxNs / 1_000_000,
                    holdSumNs / rendered / 1_000_000, maxBacklog, decodeLeadNs / 1_000_000))
                windowStart = System.nanoTime()
                received = 0; rendered = 0; maxBacklog = 0; lateSumNs = 0; lateMaxNs = 0; holdSumNs = 0
            }
        }
    }

    private companion object {
        const val TAG = "VideoDecoder"
        const val MAX_GOP_BYTES = 48L * 1024 * 1024
        const val START_RETRY_DELAY_NS = 250_000_000L
        const val STARTUP_GRACE_NS = 1_000_000_000L
        const val MIN_SCHEDULE_NS = 2_000_000L     // closer than this: just render now
        const val MAX_SCHEDULE_NS = 500_000_000L   // further than this: timestamps are off, render now
    }
}
