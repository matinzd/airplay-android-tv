package dev.matinzd.airplaytv.airplay

/**
 * JNI surface of the native AirPlay server (UxPlay's protocol core).
 * Implemented in app/src/main/cpp/airplay_jni.cpp.
 *
 * Listener callbacks arrive on native network threads, never the main thread.
 */
object AirPlayNative {
    const val SERVICE_RAOP = 0
    const val SERVICE_AIRPLAY = 1

    // reset_type_t in raop.h
    const val RESET_NOHOLD = 0
    const val RESET_RTP_SHUTDOWN = 1
    const val RESET_HLS_SHUTDOWN = 2
    const val RESET_HLS_EOS = 3
    const val RESET_RTP_TO_HLS_TEARDOWN = 5

    // Audio "ct" codes from the RTSP SETUP
    const val AUDIO_PCM = 1
    const val AUDIO_ALAC = 2
    const val AUDIO_AAC_LC = 4
    const val AUDIO_AAC_ELD = 8

    init {
        System.loadLibrary("airplay_jni")
    }

    interface Listener {
        /** RTSP SETUP from a client: a session is starting. */
        fun onClientRequest(deviceId: String?, model: String?, name: String?)
        fun onConnectionCount(count: Int)
        /** Clients were dropped (0 = nativeDisconnect, 1 = client stopped responding); the server keeps listening. */
        fun onConnectionLost(reason: Int)

        /**
         * One H.264 access unit in Annex-B format. [ptsNs] is the sender's timestamp
         * on the System.nanoTime() clock (0 until clock sync is established).
         */
        fun onVideoData(data: ByteArray, ptsNs: Long)
        /** Codec of the next mirror stream (H.265 when the sender uses 4K/HEVC). */
        fun onVideoCodec(isHevc: Boolean)
        fun onVideoReset(type: Int)
        fun onVideoFlush()

        fun onAudioFormat(ct: Int, samplesPerFrame: Int, usingScreen: Boolean, isMedia: Boolean)
        /** Decoded 44.1 kHz stereo S16LE (ALAC is decoded natively). [ptsNs] as for video. */
        fun onAudioPcm(data: ByteArray, ptsNs: Long)
        /** One raw AAC frame (AAC-ELD while mirroring). [ptsNs] as for video. */
        fun onAudioAac(data: ByteArray, ptsNs: Long)
        fun onAudioFlush()
        /** AirPlay volume in dB: -30..0, or -144 for mute. */
        fun onVolume(db: Float)
        fun onCoverArt(jpeg: ByteArray?)
        /** The sender stopped its audio stream; for AirPlay music this means paused. */
        fun onAudioStopped()
        /** Track position and length in seconds. */
        fun onProgress(positionSec: Double, durationSec: Double)
        fun onMetadata(dmap: ByteArray?)

        // AirPlay video (HLS), e.g. YouTube's AirPlay button
        fun onVideoPlay(url: String, startSec: Float)
        fun onVideoScrub(positionSec: Float)
        /** 1 = play, 0 = pause */
        fun onVideoRate(rate: Float)
        fun onVideoStop()
        /** Pause and return the current position in seconds. */
        fun onVideoPlaylistRemove(): Float
        /** [duration, position, seekStart, seekDuration, rate, stillPlaying, bufferEmpty, bufferFull] */
        fun onVideoPlaybackInfo(): DoubleArray

        // PIN pairing
        /** A new device is pairing: show this 4-digit PIN on the TV. */
        fun onDisplayPin(pin: String?)
        /** A device was admitted; remember its public key so it can reconnect without a PIN. */
        fun onRegisterClient(deviceId: String?, publicKey: String?, name: String?)
        /** A returning device: did it pair with us before? */
        fun isClientRegistered(publicKey: String): Boolean

        /** The sender accepts remote-control commands (DACP) with these IDs. */
        fun onRemoteControl(dacpId: String, activeRemote: String)
    }

    @JvmStatic
    external fun nativeStart(
        listener: Listener,
        name: String,
        hwAddr: ByteArray,
        keyFile: String,
        width: Int,
        height: Int,
        fps: Int,
        /** BCP-47 tag used to pick HLS audio/subtitle tracks, e.g. "sv-SE" */
        language: String,
        /** New devices must enter a PIN shown on the TV. */
        requirePin: Boolean,
        /** Offer H.265 mirroring (feature bit 42; 4K when the display allows it). */
        allowH265: Boolean,
        debug: Boolean,
    ): Int

    @JvmStatic external fun nativeStop()
    /** Drops all client connections; the server keeps listening. */
    @JvmStatic external fun nativeDisconnect()
    /** Expire the PIN on screen; the next pairing gets a new one. */
    @JvmStatic external fun nativeResetPin()
    @JvmStatic external fun nativeServiceName(service: Int): String?
    @JvmStatic external fun nativeServicePort(service: Int): Int
    @JvmStatic external fun nativeServiceTxt(service: Int): ByteArray?
}
