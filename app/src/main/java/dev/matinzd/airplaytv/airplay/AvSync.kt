package dev.matinzd.airplaytv.airplay

import android.util.Log

/**
 * Lip-sync for screen mirroring and AirPlay music.
 *
 * Every audio and video packet carries the sender's timestamp, converted to
 * System.nanoTime() by the native layer. Each stream reports how much latency
 * it needs (how late it would otherwise be). Both streams are then presented at
 * `timestamp + latency`, with the latency set by the slower stream plus a margin.
 *
 * Raised at once when a stream needs more; lowered slowly when it needs less, except
 * for AirPlay music, where a steady latency matters more than a low one. While
 * mirroring it can go negative (the anchor packet arrived late).
 */
class AvSync(private val clock: () -> Long = System::nanoTime) {
    @Volatile
    var latencyNs = 0L
        private set

    /** Set when the sender streams audio without its screen (AirPlay music). */
    @Volatile
    var audioOnly = false

    /**
     * Low-latency mirroring: video ignores what audio needs (no lip-sync); audio keeps
     * its own latency so it still plays smoothly, just behind the picture.
     */
    @Volatile
    var decoupled = false

    /**
     * Set by the native layer when audio and video had to be anchored on different
     * clocks (one stream restarted with new timestamps). Lip sync is unknown then, so
     * holding video back for audio would only add delay: the streams follow their own
     * needs, as in low-latency mode, but video is still scheduled smoothly.
     */
    @Volatile
    var independentClocks = false

    /** Low-latency mode while mirroring: video is shown as soon as it is decoded. */
    val immediateVideo get() = decoupled && !audioOnly

    private val videoAlone get() = (decoupled || independentClocks) && !audioOnly

    @Volatile
    private var ownAudioLatencyNs = 0L

    /** The latency audio is played at: the shared one, or its own when video follows only itself. */
    val audioLatencyNs get() = if (videoAlone) ownAudioLatencyNs else latencyNs

    // A single late video frame (a Wi-Fi hiccup) just shows late; only repeated
    // lateness should hold every later frame back, so video ignores its top sample.
    private val video = Window(ignoreTopSample = true)
    private val audio = Window()
    private var lastDecreaseNs = 0L
    private var lastLogNs = 0L

    /** [requiredNs]: how far behind its timestamp a decoded frame becomes ready. */
    fun reportVideo(requiredNs: Long) = report(video, requiredNs)

    /** [requiredNs]: arrival lateness plus the audio output pipeline's latency. */
    fun reportAudio(requiredNs: Long) = report(audio, requiredNs)

    @Synchronized
    fun reset() {
        latencyNs = 0
        ownAudioLatencyNs = 0
        audioOnly = false
        independentClocks = false
        video.reset()
        audio.reset()
    }

    @Synchronized
    private fun report(window: Window, requiredNs: Long) {
        // Frames replayed from the keyframe cache, or a clock not yet in sync.
        if (requiredNs > MAX_LATENCY_NS || requiredNs < -MAX_EARLY_NS) return
        val now = clock()
        window.add(requiredNs, now)

        if (videoAlone) {
            // Audio follows only itself (raised at once, lowered slowly); video below
            // then follows only itself too.
            val audioNeed = audio.max(now)
            if (audioNeed != Window.NONE) {
                val target = (audioNeed + MARGIN_NS).coerceIn(-MAX_LATENCY_NS, MAX_LATENCY_NS)
                if (target > ownAudioLatencyNs || ownAudioLatencyNs - target > HYSTERESIS_NS) ownAudioLatencyNs = target
            }
        }
        val needed = if (videoAlone) video.max(now) else maxOf(video.max(now), audio.max(now))
        if (needed == Window.NONE) return
        // Mirroring wants the lowest latency that works. Music arrives in bursts: keep a
        // wider margin, start near where it settles (~0.7 s here) and never lower it, so
        // it doesn't move up and down (each move is a gap or a drop you can hear).
        val margin = if (audioOnly) AUDIO_ONLY_MARGIN_NS else MARGIN_NS
        // Negative is fine while mirroring: the clock is anchored on the first packet's
        // arrival, and if that packet was late, everything after looks early.
        var target = (needed + margin).coerceIn(if (audioOnly) 0 else -MAX_LATENCY_NS, MAX_LATENCY_NS)
        if (audioOnly) target = maxOf(target, AUDIO_ONLY_START_NS)
        val current = latencyNs
        if (target > current) {
            latencyNs = target
        } else if (!audioOnly && current - target > HYSTERESIS_NS && now - lastDecreaseNs > 1_000_000_000L) {
            // Shed a quarter of the excess per second (at least 10 ms).
            latencyNs = current - maxOf((current - target) / 4, minOf(current - target, STEP_DOWN_NS))
            lastDecreaseNs = now
        }
        if (Diagnostics.enabled && now - lastLogNs > 5_000_000_000L) {
            lastLogNs = now
            Log.i(TAG, "latency %d ms (video needs %s, audio needs %s)".format(
                latencyNs / MS, video.describe(now), audio.describe(now)))
        }
    }

    /**
     * Max over the last 1-2 s (or the second-highest value, with [ignoreTopSample]);
     * inactive after 2 s without samples.
     */
    private class Window(private val ignoreTopSample: Boolean = false) {
        private var current = Bucket()
        private var previous = Bucket()
        private var startNs = 0L
        private var lastSampleNs = 0L

        private class Bucket {
            var top = NONE
            var second = NONE
            fun value(skipTop: Boolean) = if (skipTop) second else top
            fun add(value: Long) {
                if (value > top) { second = top; top = value } else if (value > second) second = value
            }
        }

        fun add(value: Long, now: Long) {
            if (now - startNs > 1_000_000_000L) {
                previous = current
                current = Bucket()
                startNs = now
            }
            current.add(value)
            lastSampleNs = now
        }

        fun max(now: Long) = if (now - lastSampleNs > 2_000_000_000L) NONE
        else maxOf(current.value(ignoreTopSample), previous.value(ignoreTopSample))

        fun describe(now: Long) = max(now).let { if (it == NONE) "-" else "${it / MS} ms" }

        fun reset() {
            current = Bucket(); previous = Bucket(); startNs = 0; lastSampleNs = 0
        }

        companion object {
            const val NONE = Long.MIN_VALUE / 4
        }
    }

    companion object {
        private const val TAG = "AvSync"
        const val MS = 1_000_000L
        private const val MARGIN_NS = 20 * MS        // covers vsync and scheduling jitter
        private const val AUDIO_ONLY_MARGIN_NS = 150 * MS
        private const val AUDIO_ONLY_START_NS = 700 * MS
        private const val HYSTERESIS_NS = 30 * MS
        private const val STEP_DOWN_NS = 10 * MS
        const val MAX_LATENCY_NS = 1_000 * MS
        private const val MAX_EARLY_NS = 5_000 * MS  // music arrives ~2 s early
    }
}
