package dev.matinzd.airplaytv.airplay

import dev.matinzd.airplaytv.airplay.AvSync.Companion.MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvSyncTest {
    private var now = 10_000 * MS
    private val sync = AvSync { now }

    /** Reports [needMs] for [seconds], every 100 ms, and returns the latencies seen. */
    private fun feed(seconds: Int, needMs: Long, report: (Long) -> Unit): List<Long> =
        (1..seconds * 10).map {
            now += 100 * MS
            report(needMs * MS)
            sync.latencyNs
        }

    @Test
    fun audioNeedRaisesLatencyAtOnce() {
        sync.reportAudio(100 * MS)
        assertEquals(120 * MS, sync.latencyNs)
    }

    @Test
    fun latencyFollowsTheSlowerStream() {
        sync.reportAudio(100 * MS)
        sync.reportVideo(150 * MS)
        sync.reportVideo(150 * MS)
        assertEquals(170 * MS, sync.latencyNs)
    }

    @Test
    fun videoIgnoresOneLateFrame() {
        sync.reportVideo(10 * MS)
        sync.reportVideo(10 * MS)
        sync.reportVideo(300 * MS)
        assertEquals(30 * MS, sync.latencyNs)
    }

    @Test
    fun mirroringLatencyCanBeNegative() {
        // Starts at 0, so it gets there by the slow decrease.
        feed(seconds = 30, needMs = -200, report = sync::reportAudio)
        assertTrue(sync.latencyNs in -180 * MS..-150 * MS)
    }

    @Test
    fun ignoresImpossibleValues() {
        sync.reportAudio(AvSync.MAX_LATENCY_NS + 1)
        sync.reportAudio(-6_000 * MS)
        assertEquals(0L, sync.latencyNs)
    }

    @Test
    fun latencyDecreasesSlowlyWhenNeedDrops() {
        sync.reportAudio(300 * MS)
        val seen = feed(seconds = 60, needMs = 50, report = sync::reportAudio).distinct()
        // Never rises again, and sheds a quarter of the excess (250 ms) in the first step.
        assertEquals(seen, seen.sortedDescending())
        assertEquals(320 * MS, seen.first())
        assertEquals(320 * MS - 250 * MS / 4, seen[1])
        // Settles within the 30 ms hysteresis above the 70 ms target.
        assertTrue(sync.latencyNs in 70 * MS..100 * MS)
    }

    @Test
    fun decreasesAtMostOncePerSecond() {
        sync.reportAudio(500 * MS)
        val seen = feed(seconds = 10, needMs = 0, report = sync::reportAudio)
        val drops = seen.zipWithNext().count { (a, b) -> b < a }
        assertTrue("$drops drops in 10 s", drops <= 10)
    }

    @Test
    fun stoppedStreamNoLongerCounts() {
        sync.reportAudio(300 * MS)
        now += 2_500 * MS
        sync.reportVideo(50 * MS)
        sync.reportVideo(50 * MS)
        // Audio is inactive after 2 s, so only video's 70 ms target is left.
        assertTrue(sync.latencyNs < 320 * MS)
    }

    @Test
    fun musicStartsAt700MsWithWideMargin() {
        sync.audioOnly = true
        sync.reportAudio(100 * MS)
        assertEquals(700 * MS, sync.latencyNs)
        sync.reportAudio(600 * MS)
        assertEquals(750 * MS, sync.latencyNs)
    }

    @Test
    fun musicLatencyIsNeverLowered() {
        sync.audioOnly = true
        sync.reportAudio(800 * MS)
        feed(seconds = 60, needMs = 0, report = sync::reportAudio)
        assertEquals(950 * MS, sync.latencyNs)
    }

    @Test
    fun musicLatencyIsNeverNegative() {
        sync.audioOnly = true
        sync.reportAudio(-3_000 * MS)
        assertEquals(700 * MS, sync.latencyNs)
    }

    @Test
    fun latencyIsCapped() {
        sync.audioOnly = true
        sync.reportAudio(950 * MS)
        assertEquals(AvSync.MAX_LATENCY_NS, sync.latencyNs)
    }

    @Test
    fun lowLatencyModeLetsVideoIgnoreAudio() {
        sync.decoupled = true
        sync.reportAudio(300 * MS)
        sync.reportVideo(50 * MS)
        sync.reportVideo(50 * MS)
        assertEquals(70 * MS, sync.latencyNs)
        assertEquals(320 * MS, sync.audioLatencyNs)
    }

    @Test
    fun lowLatencyModeDoesNotApplyToMusic() {
        sync.decoupled = true
        sync.audioOnly = true
        sync.reportAudio(100 * MS)
        assertEquals(sync.latencyNs, sync.audioLatencyNs)
    }

    @Test
    fun withoutLowLatencyModeAudioUsesSharedLatency() {
        sync.reportAudio(300 * MS)
        sync.reportVideo(50 * MS)
        sync.reportVideo(50 * MS)
        assertEquals(320 * MS, sync.latencyNs)
        assertEquals(320 * MS, sync.audioLatencyNs)
    }

    @Test
    fun independentClocksLetVideoIgnoreAudioButKeepScheduling() {
        sync.independentClocks = true
        sync.reportAudio(300 * MS)
        sync.reportVideo(50 * MS)
        sync.reportVideo(50 * MS)
        assertEquals(70 * MS, sync.latencyNs)
        assertEquals(320 * MS, sync.audioLatencyNs)
        assertEquals(false, sync.immediateVideo)
    }

    @Test
    fun lowLatencyModeShowsVideoAtOnce() {
        sync.decoupled = true
        assertEquals(true, sync.immediateVideo)
        sync.audioOnly = true
        assertEquals(false, sync.immediateVideo)
    }

    @Test
    fun resetClearsIndependentClocks() {
        sync.independentClocks = true
        sync.reset()
        assertEquals(false, sync.independentClocks)
    }

    @Test
    fun resetClearsEverything() {
        sync.audioOnly = true
        sync.reportAudio(500 * MS)
        sync.reset()
        assertEquals(0L, sync.latencyNs)
        assertEquals(false, sync.audioOnly)
        sync.reportAudio(100 * MS)
        assertEquals(120 * MS, sync.latencyNs)
    }
}
