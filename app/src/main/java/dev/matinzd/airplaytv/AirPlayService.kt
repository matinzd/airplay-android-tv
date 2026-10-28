package dev.matinzd.airplaytv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import dev.matinzd.airplaytv.airplay.AirPlayNative
import dev.matinzd.airplaytv.airplay.AudioPlayer
import dev.matinzd.airplaytv.airplay.AvSync
import dev.matinzd.airplaytv.airplay.Diagnostics
import dev.matinzd.airplaytv.airplay.Dmap
import dev.matinzd.airplaytv.airplay.HlsPlayer
import dev.matinzd.airplaytv.airplay.MdnsAdvertiser
import dev.matinzd.airplaytv.airplay.RemoteControl
import dev.matinzd.airplaytv.airplay.VideoDecoder
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Always-on AirPlay receiver. Runs as a foreground service with a partial wake
 * lock so the listener and the mDNS advertisement survive standby, and wakes
 * the TV when an iPhone starts a session.
 */
class AirPlayService : Service(), AirPlayNative.Listener {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: ReceiverSettings
    private lateinit var advertiser: MdnsAdvertiser
    private lateinit var cpuLock: PowerManager.WakeLock
    private var wifiLock: WifiManager.WifiLock? = null

    private val sync = AvSync()
    private val audio = AudioPlayer(sync)
    private val video = VideoDecoder(sync) { width, height ->
        AirPlaySession.update { it.copy(videoWidth = width, videoHeight = height) }
    }
    private lateinit var hls: HlsPlayer
    private lateinit var remote: RemoteControl

    /** Turning the TV off ends the AirPlay session, like it does on an Apple TV. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AirPlaySession.state.connections > 0) {
                Log.i(TAG, "screen turned off, ending the AirPlay session")
                worker.execute { AirPlayNative.nativeDisconnect() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        cpuLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplaytv:server")
            .apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "airplaytv:server")
            ?.apply { setReferenceCounted(false); acquire() }

        settings = ReceiverSettings(this)
        hls = HlsPlayer(
            this,
            onVideoSize = { width, height -> AirPlaySession.update { it.copy(videoWidth = width, videoHeight = height) } },
            onEnded = { AirPlaySession.update { it.copy(hlsVideo = false) } },
            onError = { protected ->
                val error = if (protected) AirPlaySession.State.VideoError.PROTECTED else AirPlaySession.State.VideoError.FAILED
                AirPlaySession.update { it.copy(hlsVideo = false, videoError = error) }
                main.removeCallbacks(clearVideoError)
                main.postDelayed(clearVideoError, VIDEO_ERROR_MS)
            },
        )
        // Mirroring and AirPlay video never run at the same time; both draw on the playback screen.
        remote = RemoteControl(this)
        AirPlaySession.remoteCommand = { remote.send(it) }
        AirPlaySession.stopSession = { worker.execute { AirPlayNative.nativeDisconnect() } }
        AirPlaySession.surfaceSink = { surface ->
            video.setSurface(surface)
            hls.setSurface(surface)
        }
        advertiser = MdnsAdvertiser(this)
        KeepAliveJob.schedule(this)
        main.post(pauseDetector)
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        worker.execute(::startServer)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART) worker.execute(::restartServer)
        return START_STICKY
    }

    /** Settings changed: stop everything and start again with the new settings. */
    private fun restartServer() {
        Log.i(TAG, "restarting the receiver with new settings")
        advertiser.unregister()
        AirPlayNative.nativeStop()
        audio.stop()
        video.reset()
        hls.stop()
        sync.reset()
        AirPlaySession.update { it.endSession().copy(connections = 0, serverRunning = false) }
        startServer()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        worker.execute {
            advertiser.unregister()
            AirPlayNative.nativeStop()
        }
        worker.shutdown()
        main.removeCallbacks(pauseDetector)
        main.removeCallbacks(clearVideoError)
        AirPlaySession.surfaceSink = null
        AirPlaySession.remoteCommand = null
        AirPlaySession.stopSession = null
        remote.clear()
        video.release()
        hls.stop()
        audio.release()
        wifiLock?.release()
        cpuLock.release()
        AirPlaySession.update { AirPlaySession.State() }
        super.onDestroy()
    }

    private fun startServer() {
        val name = settings.name ?: deviceName()
        sync.decoupled = settings.lowLatency
        val debug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        Diagnostics.enabled = debug
        val keyFile = File(filesDir, "airplay-key.pem").absolutePath
        val language = Locale.getDefault().toLanguageTag()
        val allow4k = settings.allow4k
        val rc = AirPlayNative.nativeStart(
            this, name, hardwareAddress(), keyFile,
            if (allow4k) 3840 else 1920, if (allow4k) 2160 else 1080, 60,
            language, settings.requirePin, allow4k, debug,
        )
        if (rc != 0) {
            Log.e(TAG, "AirPlay server failed to start: $rc")
            AirPlaySession.update { it.copy(serverRunning = false, error = "Server failed to start ($rc)") }
            return
        }
        advertiser.register()
        val port = AirPlayNative.nativeServicePort(AirPlayNative.SERVICE_AIRPLAY)
        AirPlaySession.update { it.copy(serverRunning = true, serverName = name, port = port, error = null) }
    }

    /** The name shown in the iPhone's AirPlay list: the TV's own device name. */
    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    /**
     * AirPlay identifies receivers by a MAC-style device ID, and iPhones remember
     * pairings by it. Android hides the real MAC, so use a stable random one.
     */
    private fun hardwareAddress(): ByteArray {
        val prefs = getSharedPreferences("airplay", Context.MODE_PRIVATE)
        prefs.getString("hw_addr", null)?.let { saved ->
            return saved.split(":").map { it.toInt(16).toByte() }.toByteArray()
        }
        val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
        bytes[0] = ((bytes[0].toInt() and 0xfe) or 0x02).toByte() // unicast, locally administered
        prefs.edit().putString("hw_addr", bytes.joinToString(":") { "%02x".format(it) }).apply()
        return bytes
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "AirPlay receiver", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_airplay)
            .setContentTitle("AirPlay receiver")
            .setContentText("Ready for AirPlay")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---- AirPlayNative.Listener (native threads) ----

    override fun onClientRequest(deviceId: String?, model: String?, name: String?) {
        Log.i(TAG, "AirPlay session from $name ($model)")
        AirPlaySession.update { it.copy(clientName = name ?: "iPhone", clientModel = model, pin = null) }
        sync.reset()
        TvWaker.wake(this)
    }

    override fun onConnectionCount(count: Int) {
        AirPlaySession.update { if (count == 0) it.endSession().copy(connections = 0) else it.copy(connections = count) }
        if (count == 0) {
            remote.clear()
            sync.reset()
            audio.stop()
            video.reset()
            hls.stop()
            TvWaker.cancelNotification(this)
        }
    }

    override fun onConnectionLost(reason: Int) {
        Log.w(TAG, "connection lost (reason $reason)")
    }

    override fun onVideoData(data: ByteArray, ptsNs: Long) {
        val state = AirPlaySession.state
        if (state.hlsVideo) {
            // The iPhone went back to mirroring without stopping the video first:
            // free the Surface before the mirror decoder needs it.
            Log.i(TAG, "mirroring resumed while AirPlay video was active; stopping the video")
            hls.stopAndWait()
        }
        if (!state.mirroring || state.hlsVideo || state.videoError != null) {
            AirPlaySession.update { it.copy(mirroring = true, hlsVideo = false, audioOnly = false, videoError = null) }
        }
        video.onFrame(data, ptsNs)
    }

    override fun onVideoReset(type: Int) {
        when (type) {
            AirPlayNative.RESET_HLS_SHUTDOWN, AirPlayNative.RESET_HLS_EOS -> {
                hls.stop()
                AirPlaySession.update { endVideo(it.copy(hlsVideo = false)) }
            }
            AirPlayNative.RESET_NOHOLD -> {
                video.reset()
                hls.stop()
                AirPlaySession.update { endVideo(it.copy(mirroring = false, hlsVideo = false)) }
            }
            AirPlayNative.RESET_RTP_TO_HLS_TEARDOWN -> { // mirroring hands over to AirPlay video
                video.reset()
                AirPlaySession.update { it.copy(mirroring = false) }
            }
            else -> { // mirroring stopped
                video.reset()
                AirPlaySession.update { endVideo(it.copy(mirroring = false)) }
            }
        }
    }

    /** When no video is left, end the session unless audio-only AirPlay is still playing. */
    private fun endVideo(state: AirPlaySession.State) =
        if (state.showsVideo) state
        else state.copy(videoWidth = 0, videoHeight = 0, clientName = if (state.audioOnly) state.clientName else null)

    override fun onVideoCodec(isHevc: Boolean) = video.setCodec(isHevc)

    override fun onVideoFlush() = Unit

    override fun onPresentationClock(independent: Boolean) {
        if (independent != sync.independentClocks) {
            Log.i(TAG, if (independent) "audio and video on different clocks: no lip sync, each follows its own need"
                       else "audio and video share a clock")
            sync.independentClocks = independent
        }
    }

    override fun onAudioFormat(ct: Int, samplesPerFrame: Int, usingScreen: Boolean, isMedia: Boolean) {
        sync.audioOnly = !usingScreen
        audio.configure(ct, samplesPerFrame, lowLatency = usingScreen)
        // Music started or resumed from the phone: bring the now-playing screen back
        // (it was closed with Back, or the TV was off).
        if (!usingScreen) TvWaker.wake(this)
        AirPlaySession.update {
            it.copy(audioOnly = !usingScreen && !it.showsVideo)
        }
    }

    override fun onAudioPcm(data: ByteArray, ptsNs: Long) {
        audioArrived()
        audio.playPcm(data, ptsNs)
    }

    override fun onAudioAac(data: ByteArray, ptsNs: Long) {
        audioArrived()
        audio.playAac(data, ptsNs)
    }

    // Play/pause state for AirPlay music. Pausing from the phone just stops the audio
    // packets (there's no explicit message), so: no audio for a while = paused.
    @Volatile
    private var lastAudioNs = 0L

    private fun audioArrived() {
        lastAudioNs = System.nanoTime()
        if (!AirPlaySession.state.playing) {
            AirPlaySession.update { it.copy(playing = true, positionAtNs = System.nanoTime()) }
        }
    }

    private val pauseDetector = object : Runnable {
        override fun run() {
            val state = AirPlaySession.state
            if (state.audioOnly && state.playing && System.nanoTime() - lastAudioNs > PAUSE_AFTER_NS) onAudioStopped()
            main.postDelayed(this, 500)
        }
    }

    override fun onAudioFlush() = audio.flush()

    override fun onVolume(db: Float) {
        val gain = AudioPlayer.airPlayGain(db)
        audio.setVolume(gain)
        hls.setVolume(gain)
    }

    override fun onCoverArt(jpeg: ByteArray?) {
        // Pausing makes the sender stop its audio stream (UxPlay then clears the art) and
        // start a new one on resume. Keep the last cover until a new one arrives or the
        // session ends.
        val bitmap = jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return
        AirPlaySession.update { it.copy(coverArt = bitmap) }
    }

    override fun onAudioStopped() {
        AirPlaySession.update { it.copy(playing = false, positionSec = it.positionNow(), positionAtNs = 0) }
    }

    override fun onProgress(positionSec: Double, durationSec: Double) {
        AirPlaySession.update {
            it.copy(positionSec = positionSec, durationSec = durationSec, positionAtNs = System.nanoTime())
        }
    }

    override fun onMetadata(dmap: ByteArray?) {
        val track = dmap?.let(Dmap::parse) ?: return
        AirPlaySession.update { it.copy(title = track.title, artist = track.artist, album = track.album) }
    }

    override fun onVideoPlay(url: String, startSec: Float) {
        // AirPlay video doesn't go through RTSP SETUP, so wake the TV here too.
        AirPlaySession.update {
            it.copy(hlsVideo = true, mirroring = false, audioOnly = false, videoError = null, clientName = it.clientName ?: "iPhone")
        }
        TvWaker.wake(this)
        hls.play(url, startSec)
    }

    override fun onVideoScrub(positionSec: Float) = hls.seek(positionSec)

    override fun onVideoRate(rate: Float) = hls.setRate(rate)

    override fun onVideoStop() {
        hls.stop()
        AirPlaySession.update { endVideo(it.copy(hlsVideo = false)) }
    }

    override fun onVideoPlaylistRemove(): Float = hls.pauseAndGetPosition()

    override fun onVideoPlaybackInfo(): DoubleArray = hls.playbackInfo()

    private val clearVideoError = Runnable { AirPlaySession.update { it.copy(videoError = null) } }

    override fun onDisplayPin(pin: String?) {
        Log.i(TAG, "showing a pairing PIN")
        AirPlaySession.update { it.copy(pin = pin) }
        TvWaker.wake(this)
        // Hide it if nobody enters it.
        main.postDelayed({
            if (AirPlaySession.state.pin == pin) {
                worker.execute { AirPlayNative.nativeResetPin() }
                AirPlaySession.update { if (it.pin == pin) it.copy(pin = null) else it }
            }
        }, PIN_TIMEOUT_MS)
    }

    override fun onRegisterClient(deviceId: String?, publicKey: String?, name: String?) {
        if (publicKey != null && settings.requirePin) settings.remember(publicKey, name ?: deviceId ?: "device")
        AirPlaySession.update { it.copy(pin = null) }
    }

    override fun isClientRegistered(publicKey: String) = settings.isRemembered(publicKey)

    override fun onRemoteControl(dacpId: String, activeRemote: String) = remote.setSender(dacpId, activeRemote)

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL = "airplay_receiver"
        private const val NOTIFICATION_ID = 1
        private const val PIN_TIMEOUT_MS = 60_000L
        private const val VIDEO_ERROR_MS = 10_000L
        private const val PAUSE_AFTER_NS = 1_200_000_000L

        const val ACTION_RESTART = "dev.matinzd.airplaytv.RESTART"

        fun restart(context: Context) {
            context.startService(Intent(context, AirPlayService::class.java).setAction(ACTION_RESTART))
        }

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, AirPlayService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: not allowed from the background
                // right now (e.g. battery optimisation exemption missing). KeepAliveJob retries.
                Log.w(TAG, "could not start the receiver: ${e.message}")
            }
        }
    }
}
