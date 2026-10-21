/*
 * JNI bridge between UxPlay's AirPlay protocol core and the Kotlin app.
 *
 * Native side: runs the RAOP/AirPlay server, decrypts the mirror and audio
 * streams, decodes ALAC, and builds the mDNS TXT records.
 * Kotlin side (AirPlayNative.Listener): mDNS registration, MediaCodec video and
 * AAC-ELD decoding, AudioTrack output, and waking the TV.
 */

#include <jni.h>
#include <pthread.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <ctime>
#include <cstdlib>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define ALOG(prio, ...) __android_log_print(prio, "AirPlayNative", __VA_ARGS__)
#define LOG_I(...) ALOG(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOG_E(...) ALOG(ANDROID_LOG_ERROR, __VA_ARGS__)
#else
#include <cstdio>
#define LOG_I(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#define LOG_E(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

extern "C" {
#include "raop.h"
#include "dnssd.h"
#include "stream.h"
#include "logger.h"
#include "android_dnssd.h"
}
#include "ALACDecoder.h"
#include "ALACBitUtilities.h"

namespace {

constexpr const char *kNativeClass = "dev/matinzd/airplaytv/airplay/AirPlayNative";

/* Legacy AirPlay ports (UxPlay's -p option). Fixed ports keep the mDNS
   advertisement valid across server restarts. */
unsigned short kTcpPorts[3] = {7100, 7000, 7001};
unsigned short kUdpPorts[3] = {6000, 6001, 7011};

/* A client that stops sending feedback (every ~2 s) for this long is gone. */
constexpr int64_t kFeedbackTimeoutMs = 15000;

/* ALAC magic cookie for 44100 Hz / 16-bit / stereo / 352 frames per packet
   (same cookie UxPlay hands to GStreamer). */
const uint8_t kAlacCookie[] = {
    0x00, 0x00, 0x00, 0x24, 'a', 'l', 'a', 'c', 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x01, 0x60, 0x00, 0x10, 0x28, 0x0a, 0x0e, 0x02, 0x00, 0xff,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xac, 0x44};
constexpr uint32_t kAlacFramesPerPacket = 352;
constexpr uint32_t kChannels = 2;

JavaVM *g_vm = nullptr;
pthread_key_t g_env_key;

std::mutex g_server_mutex;          // guards g_raop / g_dnssd lifetime
raop_t *g_raop = nullptr;
dnssd_t *g_dnssd = nullptr;

jobject g_listener = nullptr;       // global ref to AirPlayNative.Listener
struct {
    jmethodID onClientRequest, onConnectionCount, onConnectionLost;
    jmethodID onVideoData, onVideoCodec, onVideoReset, onVideoFlush, onPresentationClock;
    jmethodID onAudioFormat, onAudioPcm, onAudioAac, onAudioFlush;
    jmethodID onVolume, onCoverArt, onMetadata;
    jmethodID onVideoPlay, onVideoScrub, onVideoRate, onVideoStop, onVideoPlaylistRemove, onVideoPlaybackInfo;
    jmethodID onDisplayPin, onRegisterClient, isClientRegistered, onRemoteControl;
    jmethodID onAudioStopped, onProgress;
} m;

/* raop keeps these pointers, so they must outlive the server. */
std::string g_lang_system;
const std::string g_empty;

std::atomic<int> g_open_connections{0};
std::atomic<int64_t> g_last_feedback_ms{0};
std::atomic<bool> g_restart_pending{false};
std::atomic<bool> g_watchdog_running{false};
std::thread g_watchdog;

std::mutex g_alac_mutex;
ALACDecoder *g_alac = nullptr;
std::vector<uint8_t> g_pcm(kAlacFramesPerPacket * kChannels * 2 * 2);

double g_client_volume = 0.0;

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

int64_t realtime_now_ns() {
    timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

int64_t monotonic_now_ns() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

/* Presentation clock. Packets carry the sender's timestamps (ntp_time_remote); an
   offset maps them onto System.nanoTime() (CLOCK_MONOTONIC), anchored on a stream's
   first packet: at its NTP-synced local time when that is plausible and wanted
   (AirPlay music), otherwise at its arrival time.

   Audio and video usually share the sender's clock (UxPlay uses one offset), but not
   always: after an AirPlay video session an iPhone's mirror audio and video were
   ~9 h apart (uptime vs wall clock). So each stream has its own offset. A stream
   adopts the other's offset when its timestamps make sense with it (that keeps lip
   sync); otherwise it anchors on its own and the two stop disturbing each other. */
template <typename... Args> void call_listener(jmethodID method, Args... args);

constexpr int64_t kNoOffset = INT64_MIN;
constexpr int64_t kPlausibleNs = 5000000000LL;  // AirPlay never schedules >5 s ahead/behind
enum { STREAM_AUDIO = 0, STREAM_VIDEO = 1 };
std::atomic<int64_t> g_offset[2] = {{kNoOffset}, {kNoOffset}};

void reset_presentation_clock() {
    g_offset[STREAM_AUDIO] = kNoOffset;
    g_offset[STREAM_VIDEO] = kNoOffset;
}

int64_t presentation_ns(uint64_t remote_ns, uint64_t local_realtime_ns, bool sender_time, int stream) {
    if (!remote_ns) return 0;
    const char *what = stream == STREAM_AUDIO ? "audio" : "video";
    int64_t now_mono = monotonic_now_ns();
    int64_t offset = g_offset[stream];
    if (offset == kNoOffset) {
        int64_t other = g_offset[1 - stream];
        if (other != kNoOffset && llabs((int64_t) remote_ns + other - now_mono) < kPlausibleNs) {
            offset = other;  // same sender clock as the other stream: share it (lip sync)
            LOG_I("presentation clock: %s shares the %s clock", what, stream == STREAM_AUDIO ? "video" : "audio");
            call_listener(m.onPresentationClock, (jboolean) false);
        } else {
            int64_t anchor = now_mono;
            if (sender_time && local_realtime_ns) {
                int64_t local_mono = (int64_t) local_realtime_ns - realtime_now_ns() + now_mono;
                if (llabs(local_mono - now_mono) < 10000000000LL) anchor = local_mono;
            }
            offset = anchor - (int64_t) remote_ns;
            LOG_I("presentation clock: %s anchored on %s%s", what,
                  anchor == now_mono ? "arrival time" : "the sender's NTP time",
                  other != kNoOffset ? " (different clock from the other stream)" : "");
            // Two streams on different clocks: their relative timing is unknown, so the
            // app must not hold one back for the other.
            if (other != kNoOffset) call_listener(m.onPresentationClock, (jboolean) true);
        }
        g_offset[stream] = offset;
    }
    int64_t pts = (int64_t) remote_ns + offset;
    if (llabs(pts - now_mono) > kPlausibleNs) {
        // This stream switched clocks mid-way (e.g. a restarted audio stream): re-anchor it.
        LOG_I("%s timestamp %lld ms from now, re-anchoring", what, (long long) ((pts - now_mono) / 1000000));
        offset = now_mono - (int64_t) remote_ns;
        g_offset[stream] = offset;
        pts = (int64_t) remote_ns + offset;
        if (g_offset[1 - stream] != kNoOffset) call_listener(m.onPresentationClock, (jboolean) true);
    }
    return pts > 0 ? pts : 0;
}

void detach_thread(void *) {
    if (g_vm) g_vm->DetachCurrentThread();
}

/* Native threads created by UxPlay attach lazily and detach when they exit. */
JNIEnv *get_env() {
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
#ifdef __ANDROID__
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
#else
    if (g_vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return nullptr;
#endif
    pthread_setspecific(g_env_key, env);
    return env;
}

void check_exception(JNIEnv *env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

jstring to_jstring(JNIEnv *env, const char *s) {
    return s ? env->NewStringUTF(s) : nullptr;
}

jbyteArray to_jbytes(JNIEnv *env, const void *data, int len) {
    if (!data || len <= 0) return nullptr;
    jbyteArray arr = env->NewByteArray(len);
    if (arr) env->SetByteArrayRegion(arr, 0, len, static_cast<const jbyte *>(data));
    return arr;
}

template <typename... Args>
void call_listener(jmethodID method, Args... args) {
    if (!g_listener) return;
    JNIEnv *env = get_env();
    if (!env) return;
    env->CallVoidMethod(g_listener, method, args...);
    check_exception(env);
}

void call_with_bytes(jmethodID method, const void *data, int len) {
    if (!g_listener) return;
    JNIEnv *env = get_env();
    if (!env) return;
    jbyteArray arr = to_jbytes(env, data, len);
    env->CallVoidMethod(g_listener, method, arr);
    check_exception(env);
    if (arr) env->DeleteLocalRef(arr);
}

void call_with_bytes_ts(jmethodID method, const void *data, int len, int64_t pts_ns) {
    if (!g_listener) return;
    JNIEnv *env = get_env();
    if (!env) return;
    jbyteArray arr = to_jbytes(env, data, len);
    env->CallVoidMethod(g_listener, method, arr, (jlong) pts_ns);
    check_exception(env);
    if (arr) env->DeleteLocalRef(arr);
}

/* ---- server restart (lost client) ---------------------------------------- */

/* reason: 0 = requested by the app, 1 = client stopped responding, 2 = unsupported stream */
void restart_httpd(int reason) {
    if (g_restart_pending.exchange(true)) return;
    // Never stop httpd from one of its own threads: do it on a fresh thread.
    std::thread([reason] {
        {
            std::lock_guard<std::mutex> lock(g_server_mutex);
            if (g_raop) {
                LOG_I("restarting AirPlay server (reason %d)", reason);
                raop_stop_httpd(g_raop);
                raop_remove_known_connections(g_raop);
                unsigned short port = raop_get_port(g_raop);
                raop_start_httpd(g_raop, &port);
                raop_set_port(g_raop, port);
            }
        }
        g_open_connections = 0;
        call_listener(m.onConnectionCount, (jint) 0);
        call_listener(m.onConnectionLost, (jint) reason);
        g_restart_pending = false;
    }).detach();
}

void watchdog_loop() {
    while (g_watchdog_running) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        if (g_open_connections > 0 && !g_restart_pending &&
            now_ms() - g_last_feedback_ms > kFeedbackTimeoutMs) {
            LOG_I("no client feedback for %lld ms", (long long) (now_ms() - g_last_feedback_ms));
            restart_httpd(1);
        }
    }
}

/* ---- UxPlay callbacks ----------------------------------------------------- */

void cb_log(void *, int level, const char *msg) {
#ifdef __ANDROID__
    int prio = level <= LOGGER_ERR ? ANDROID_LOG_ERROR
             : level == LOGGER_WARNING ? ANDROID_LOG_WARN
             : level >= LOGGER_DEBUG ? ANDROID_LOG_DEBUG : ANDROID_LOG_INFO;
    __android_log_print(prio, "UxPlay", "%s", msg);
#else
    fprintf(stderr, "UxPlay[%d] %s\n", level, msg);
#endif
}

void cb_conn_init(void *) {
    int n = ++g_open_connections;
    g_last_feedback_ms = now_ms();
    call_listener(m.onConnectionCount, (jint) n);
}

void cb_conn_destroy(void *) {
    int n = --g_open_connections;
    if (n < 0) n = g_open_connections = 0;
    if (n == 0) reset_presentation_clock();
    call_listener(m.onConnectionCount, (jint) n);
}

void cb_conn_feedback(void *) {
    g_last_feedback_ms = now_ms();
}

void cb_conn_reset(void *, int reason) {
    LOG_I("conn_reset reason=%d", reason);
    restart_httpd(reason);
}

/* RTSP SETUP from a client: this is the moment to wake the TV. */
void cb_report_client_request(void *, char *deviceid, char *model, char *name, bool *admit) {
    *admit = true;
    reset_presentation_clock();
    if (!g_listener) return;
    JNIEnv *env = get_env();
    if (!env) return;
    jstring jd = to_jstring(env, deviceid), jm = to_jstring(env, model), jn = to_jstring(env, name);
    env->CallVoidMethod(g_listener, m.onClientRequest, jd, jm, jn);
    check_exception(env);
    if (jd) env->DeleteLocalRef(jd);
    if (jm) env->DeleteLocalRef(jm);
    if (jn) env->DeleteLocalRef(jn);
}

int cb_video_set_codec(void *, video_codec_t codec) {
    LOG_I("mirror codec %s", codec == VIDEO_CODEC_H265 ? "H.265" : "H.264");
    call_listener(m.onVideoCodec, (jboolean) (codec == VIDEO_CODEC_H265));
    return 0;
}

/* One access unit, Annex-B (start codes), SPS/PPS prepended to IDR frames. */
void cb_video_process(void *, raop_ntp_t *, video_decode_struct *data) {
    if (!g_listener || data->data_len <= 0) return;
    JNIEnv *env = get_env();
    if (!env) return;
    jbyteArray arr = to_jbytes(env, data->data, data->data_len);
    jlong pts = presentation_ns(data->ntp_time_remote, data->ntp_time_local, false, STREAM_VIDEO);
    env->CallVoidMethod(g_listener, m.onVideoData, arr, pts);
    check_exception(env);
    if (arr) env->DeleteLocalRef(arr);
}

void cb_video_reset(void *, reset_type_t type) {
    LOG_I("video_reset type=%d", (int) type);
    reset_presentation_clock();
    if (g_raop) {
        switch (type) {
        case RESET_TYPE_NOHOLD:            // a new client took over
            raop_destroy_airplay_video(g_raop, -1);
            raop_remove_known_connections(g_raop);  // only flags connections for removal
            break;
        case RESET_TYPE_RTP_SHUTDOWN:      // mirroring stopped
            raop_remove_known_connections(g_raop);
            break;
        case RESET_TYPE_HLS_SHUTDOWN:      // AirPlay video stopped
            raop_destroy_airplay_video(g_raop, -1);
            raop_remove_hls_connections(g_raop);
            break;
        default:                           // HLS_EOS, RTP_TO_HLS_TEARDOWN: keep connections
            break;
        }
    }
    call_listener(m.onVideoReset, (jint) type);
}

void cb_video_flush(void *) { call_listener(m.onVideoFlush); }
void cb_video_pause(void *) {}
void cb_video_resume(void *) {}

void cb_audio_get_format(void *, unsigned char *ct, unsigned short *spf, bool *using_screen,
                         bool *is_media, uint64_t *audio_format) {
    LOG_I("audio format ct=%d spf=%d screen=%d media=%d fmt=0x%llx", *ct, *spf, *using_screen,
          *is_media, (unsigned long long) *audio_format);
    if (*ct == 2) {
        std::lock_guard<std::mutex> lock(g_alac_mutex);
        delete g_alac;
        g_alac = new ALACDecoder();
        if (g_alac->Init((void *) kAlacCookie, sizeof(kAlacCookie)) != 0) {
            LOG_E("ALAC decoder init failed");
            delete g_alac;
            g_alac = nullptr;
        }
    }
    call_listener(m.onAudioFormat, (jint) *ct, (jint) *spf, (jboolean) *using_screen,
                  (jboolean) *is_media);
}

void cb_audio_process(void *, raop_ntp_t *, audio_decode_struct *data) {
    if (data->data_len <= 0) return;
    int64_t pts_ns = presentation_ns(data->ntp_time_remote, data->ntp_time_local, data->ct == 2, STREAM_AUDIO);
    switch (data->ct) {
    case 1:  // LPCM S16LE
        call_with_bytes_ts(m.onAudioPcm, data->data, data->data_len, pts_ns);
        break;
    case 2: {  // ALAC -> PCM
        std::lock_guard<std::mutex> lock(g_alac_mutex);
        if (!g_alac) return;
        BitBuffer bits;
        BitBufferInit(&bits, data->data, (uint32_t) data->data_len);
        uint32_t frames = 0;
        if (g_alac->Decode(&bits, g_pcm.data(), kAlacFramesPerPacket, kChannels, &frames) == 0 && frames) {
            call_with_bytes_ts(m.onAudioPcm, g_pcm.data(), (int) (frames * kChannels * 2), pts_ns);
        }
        break;
    }
    case 4:  // AAC-LC
    case 8:  // AAC-ELD (screen mirroring)
        call_with_bytes_ts(m.onAudioAac, data->data, data->data_len, pts_ns);
        break;
    default:
        break;
    }
}

void cb_audio_flush(void *) { call_listener(m.onAudioFlush); }

double cb_audio_set_client_volume(void *) { return g_client_volume; }

/* AirPlay volume: -30..0 dB, -144 = mute. Kotlin maps it to AudioTrack gain. */
void cb_audio_set_volume(void *, float volume) {
    g_client_volume = volume;
    call_listener(m.onVolume, (jfloat) volume);
}

void cb_audio_set_coverart(void *, const void *buffer, int buflen) {
    call_with_bytes(m.onCoverArt, buffer, buflen);
}

/* The sender stopped its audio stream (TEARDOWN): for AirPlay music this is a pause. */
void cb_audio_stop_coverart_rendering(void *) { call_listener(m.onAudioStopped); }

void cb_audio_set_metadata(void *, const void *buffer, int buflen) {
    call_with_bytes(m.onMetadata, buffer, buflen);
}

/* ---- PIN pairing ----
   With a PIN required, a new device must enter a 4-digit code shown on the TV. It is
   then remembered (by its public key) and reconnects without a code. */
void cb_register_client(void *, const char *device_id, const char *pk, const char *name) {
    if (g_raop) raop_set_plist(g_raop, "pin", 0);  // paired: next device gets a new random PIN
    if (!g_listener || !pk) return;
    JNIEnv *env = get_env();
    if (!env) return;
    jstring jd = to_jstring(env, device_id), jp = to_jstring(env, pk), jn = to_jstring(env, name);
    env->CallVoidMethod(g_listener, m.onRegisterClient, jd, jp, jn);
    check_exception(env);
    if (jd) env->DeleteLocalRef(jd);
    if (jp) env->DeleteLocalRef(jp);
    if (jn) env->DeleteLocalRef(jn);
}

/* A returning device (pair-verify without a new PIN): is it one we registered? */
bool cb_check_register(void *, const char *pk) {
    if (!g_listener || !pk) return false;
    JNIEnv *env = get_env();
    if (!env) return false;
    jstring jp = env->NewStringUTF(pk);
    jboolean known = env->CallBooleanMethod(g_listener, m.isClientRegistered, jp);
    check_exception(env);
    env->DeleteLocalRef(jp);
    return known;
}

const char *cb_passwd(void *, int *len) { *len = 0; return nullptr; }

void cb_display_pin(void *, char *pin) {
    LOG_I("PIN requested by a new device");
    // UxPlay's random PIN is single-use: the first pair-setup attempt clears it. iPhones
    // first retry an old saved code by themselves, which used up the new one before the
    // user could type it. Hold it as a fixed PIN (+10000) until pairing succeeds or the
    // app resets it.
    if (g_raop && pin) raop_set_plist(g_raop, "pin", atoi(pin) + 10000);
    if (!g_listener) return;
    JNIEnv *env = get_env();
    if (!env) return;
    jstring jp = to_jstring(env, pin);
    env->CallVoidMethod(g_listener, m.onDisplayPin, jp);
    check_exception(env);
    if (jp) env->DeleteLocalRef(jp);
}
/* The sender's remote-control (DACP) ID: lets the TV remote pause and skip tracks. */
void cb_export_dacp(void *, const char *active_remote, const char *dacp_id) {
    if (!g_listener || !active_remote || !dacp_id) return;
    JNIEnv *env = get_env();
    if (!env) return;
    jstring jd = env->NewStringUTF(dacp_id), ja = env->NewStringUTF(active_remote);
    env->CallVoidMethod(g_listener, m.onRemoteControl, jd, ja);
    check_exception(env);
    env->DeleteLocalRef(jd);
    env->DeleteLocalRef(ja);
}
void cb_mirror_video_running(void *, bool) {}
void cb_video_report_size(void *, float *, float *, float *, float *) {}
void cb_audio_remote_control_id(void *, const char *, const char *) {}
/* Track position, as RTP sample counts (44.1 kHz): start, now, end of the track. */
void cb_audio_set_progress(void *, uint32_t *start, uint32_t *curr, uint32_t *end) {
    double position = (uint32_t) (*curr - *start) / 44100.0;  // uint32 maths handles wrap-around
    double duration = (uint32_t) (*end - *start) / 44100.0;
    call_listener(m.onProgress, (jdouble) position, (jdouble) duration);
}

/* ---- AirPlay video (HLS, e.g. YouTube's AirPlay button) ----
   UxPlay fetches the playlists through the iPhone and serves them on
   http://localhost:<port>/; Kotlin plays that URL with ExoPlayer. */
void cb_on_video_play(void *, const char *location, const float start_position) {
    LOG_I("on_video_play %s at %.1f s", location, start_position);
    if (!g_listener) return;
    JNIEnv *env = get_env();
    if (!env) return;
    jstring url = to_jstring(env, location);
    env->CallVoidMethod(g_listener, m.onVideoPlay, url, (jfloat) start_position);
    check_exception(env);
    if (url) env->DeleteLocalRef(url);
}

void cb_on_video_scrub(void *, const float position) { call_listener(m.onVideoScrub, (jfloat) position); }
void cb_on_video_rate(void *, const float rate) { call_listener(m.onVideoRate, (jfloat) rate); }
void cb_on_video_stop(void *) { call_listener(m.onVideoStop); }

/* Pause, and report where playback was (UxPlay stores it as the resume position). */
float cb_on_video_playlist_remove(void *) {
    if (!g_listener) return 0.0f;
    JNIEnv *env = get_env();
    if (!env) return 0.0f;
    jfloat position = env->CallFloatMethod(g_listener, m.onVideoPlaylistRemove);
    check_exception(env);
    return position;
}

/* Polled by the iPhone (about once a second) to drive its progress bar. */
void cb_on_video_acquire_playback_info(void *, playback_info_t *info) {
    info->ready_to_play = true;
    info->playback_likely_to_keep_up = true;
    if (!g_listener) return;
    JNIEnv *env = get_env();
    if (!env) return;
    // [duration, position, seekStart, seekDuration, rate, stillPlaying, bufferEmpty, bufferFull]
    auto arr = (jdoubleArray) env->CallObjectMethod(g_listener, m.onVideoPlaybackInfo);
    check_exception(env);
    if (!arr) return;
    jdouble v[8] = {0};
    env->GetDoubleArrayRegion(arr, 0, 8, v);
    env->DeleteLocalRef(arr);
    info->duration = v[0];
    info->position = v[1];
    info->seek_start = v[2];
    info->seek_duration = v[3];
    info->rate = (float) v[4];
    info->playback_buffer_empty = v[6] != 0;
    info->playback_buffer_full = v[7] != 0;
    if (v[5] == 0) {  // finished: tells the client the video is over
        info->position = -1.0;
        info->duration = -1.0;
    }
}

/* ---- server lifecycle ----------------------------------------------------- */

void set_features(dnssd_t *d, bool pin, bool h265) {
    /* UxPlay's start_dnssd() defaults with HLS ("-hls"), plus optional H.265 ("-h265")
       and PIN pairing ("-pin"). */
    static const int on_bits[] = {0 /* video */, 1, 2, 4 /* HLS */, 5, 6, 7, 9, 10, 11, 12, 13,
                                  14, 15, 16, 17, 18, 19, 20, 21, 22, 25, 28, 30};
    for (int bit = 0; bit < 64; bit++) dnssd_set_airplay_features(d, bit, 0);
    for (int bit : on_bits) dnssd_set_airplay_features(d, bit, 1);
    dnssd_set_airplay_features(d, 27, pin);   // legacy pairing: needed for PIN pairing (UxPlay -pin)
    dnssd_set_airplay_features(d, 42, h265);  // screen multi-codec: H.265 (4K) mirroring
}

void stop_server_locked() {
    if (g_raop) {
        raop_destroy(g_raop);
        g_raop = nullptr;
    }
    if (g_dnssd) {
        dnssd_destroy(g_dnssd);
        g_dnssd = nullptr;
    }
}

void stop_watchdog() {
    g_watchdog_running = false;
    if (g_watchdog.joinable()) g_watchdog.join();
}

/* ---- JNI entry points ------------------------------------------------------ */

jint native_start(JNIEnv *env, jclass, jobject listener, jstring jname, jbyteArray jhw,
                  jstring jkeyfile, jint width, jint height, jint fps, jstring jlang,
                  jboolean pin, jboolean h265, jboolean debug) {
    std::lock_guard<std::mutex> lock(g_server_mutex);
    if (g_raop) return 0;

    if (g_listener) env->DeleteGlobalRef(g_listener);
    g_listener = env->NewGlobalRef(listener);
    jclass cls = env->GetObjectClass(listener);
    m.onClientRequest = env->GetMethodID(cls, "onClientRequest",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    m.onConnectionCount = env->GetMethodID(cls, "onConnectionCount", "(I)V");
    m.onConnectionLost = env->GetMethodID(cls, "onConnectionLost", "(I)V");
    m.onVideoData = env->GetMethodID(cls, "onVideoData", "([BJ)V");
    m.onVideoCodec = env->GetMethodID(cls, "onVideoCodec", "(Z)V");
    m.onVideoReset = env->GetMethodID(cls, "onVideoReset", "(I)V");
    m.onVideoFlush = env->GetMethodID(cls, "onVideoFlush", "()V");
    m.onPresentationClock = env->GetMethodID(cls, "onPresentationClock", "(Z)V");
    m.onAudioFormat = env->GetMethodID(cls, "onAudioFormat", "(IIZZ)V");
    m.onAudioPcm = env->GetMethodID(cls, "onAudioPcm", "([BJ)V");
    m.onAudioAac = env->GetMethodID(cls, "onAudioAac", "([BJ)V");
    m.onAudioFlush = env->GetMethodID(cls, "onAudioFlush", "()V");
    m.onVolume = env->GetMethodID(cls, "onVolume", "(F)V");
    m.onCoverArt = env->GetMethodID(cls, "onCoverArt", "([B)V");
    m.onMetadata = env->GetMethodID(cls, "onMetadata", "([B)V");
    m.onVideoPlay = env->GetMethodID(cls, "onVideoPlay", "(Ljava/lang/String;F)V");
    m.onVideoScrub = env->GetMethodID(cls, "onVideoScrub", "(F)V");
    m.onVideoRate = env->GetMethodID(cls, "onVideoRate", "(F)V");
    m.onVideoStop = env->GetMethodID(cls, "onVideoStop", "()V");
    m.onVideoPlaylistRemove = env->GetMethodID(cls, "onVideoPlaylistRemove", "()F");
    m.onVideoPlaybackInfo = env->GetMethodID(cls, "onVideoPlaybackInfo", "()[D");
    m.onDisplayPin = env->GetMethodID(cls, "onDisplayPin", "(Ljava/lang/String;)V");
    m.onRegisterClient = env->GetMethodID(cls, "onRegisterClient",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    m.isClientRegistered = env->GetMethodID(cls, "isClientRegistered", "(Ljava/lang/String;)Z");
    m.onRemoteControl = env->GetMethodID(cls, "onRemoteControl", "(Ljava/lang/String;Ljava/lang/String;)V");
    m.onAudioStopped = env->GetMethodID(cls, "onAudioStopped", "()V");
    m.onProgress = env->GetMethodID(cls, "onProgress", "(DD)V");
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) {  // a listener method is missing
        env->ExceptionDescribe();
        env->ExceptionClear();
        return -1;
    }

    const char *name = env->GetStringUTFChars(jname, nullptr);
    const char *keyfile = env->GetStringUTFChars(jkeyfile, nullptr);
    std::vector<char> hw(env->GetArrayLength(jhw));
    env->GetByteArrayRegion(jhw, 0, (jsize) hw.size(), reinterpret_cast<jbyte *>(hw.data()));

    // UxPlay wants the device ID as "aa:bb:cc:dd:ee:ff"
    std::string mac_str;
    for (size_t i = 0; i < hw.size(); i++) {
        char part[4];
        snprintf(part, sizeof(part), i ? ":%02x" : "%02x", (unsigned char) hw[i]);
        mac_str += part;
    }

    int rc = 0;
    int err = 0;
    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));

    // pin_pw 1: on-screen PIN for new devices (advertised as pw=true in the TXT records)
    g_dnssd = dnssd_init(name, (int) strlen(name), hw.data(), (int) hw.size(), pin ? 1 : 0, &err);
    if (err || !g_dnssd) { LOG_E("dnssd_init failed: %d", err); rc = -2; goto done; }
    set_features(g_dnssd, pin, h265);

    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.conn_reset = cb_conn_reset;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = cb_passwd;
    cbs.export_dacp = cb_export_dacp;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec = cb_video_set_codec;
    cbs.mirror_video_running = cb_mirror_video_running;
    cbs.video_report_size = cb_video_report_size;
    cbs.audio_remote_control_id = cb_audio_remote_control_id;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_on_video_stop;
    cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;

    ntp_global_init();
    g_raop = raop_init(&cbs);
    if (!g_raop) { LOG_E("raop_init failed"); rc = -3; goto done; }
    raop_set_log_callback(g_raop, cb_log, nullptr);
    raop_set_log_level(g_raop, debug ? LOGGER_DEBUG : LOGGER_INFO);
    if (raop_init2(g_raop, 1 /* nohold: a new client takes over */, mac_str.c_str(), keyfile)) {
        LOG_E("raop_init2 failed");
        free(g_raop);  // as UxPlay does: raop_init2 failed before httpd/pairing exist
        g_raop = nullptr;
        rc = -4;
        goto done;
    }
    raop_set_plist(g_raop, "width", width);
    raop_set_plist(g_raop, "height", height);
    raop_set_plist(g_raop, "refreshRate", fps);
    raop_set_plist(g_raop, "maxFPS", fps);
    raop_set_plist(g_raop, "hls", 1);
    if (pin) raop_set_plist(g_raop, "pin", 0);  // a new random PIN for each pairing
    {
        // Preferred audio/subtitle language for HLS, e.g. "sv-SE".
        const char *lang = env->GetStringUTFChars(jlang, nullptr);
        g_lang_system = lang;
        env->ReleaseStringUTFChars(jlang, lang);
        raop_set_lang(g_raop, g_empty.c_str(), g_empty.c_str(), g_lang_system.c_str());
    }
    raop_set_tcp_ports(g_raop, kTcpPorts);
    raop_set_udp_ports(g_raop, kUdpPorts);
    {
        unsigned short port = raop_get_port(g_raop);
        if (raop_start_httpd(g_raop, &port) < 0) { LOG_E("raop_start_httpd failed"); rc = -5; goto done; }
        raop_set_port(g_raop, port);
        raop_set_dnssd(g_raop, g_dnssd);  // copies the pairing public key into the TXT records
        if (dnssd_register_raop(g_dnssd, port) || dnssd_register_airplay(g_dnssd, port)) {
            LOG_E("building mDNS records failed"); rc = -6; goto done;
        }
        LOG_I("AirPlay server '%s' listening on port %d (features 0x%llx)", name, port,
              (unsigned long long) dnssd_get_airplay_features(g_dnssd));
    }

    g_open_connections = 0;
    g_watchdog_running = true;
    g_watchdog = std::thread(watchdog_loop);

done:
    if (rc) stop_server_locked();
    env->ReleaseStringUTFChars(jname, name);
    env->ReleaseStringUTFChars(jkeyfile, keyfile);
    return rc;
}

void native_stop(JNIEnv *env, jclass) {
    stop_watchdog();
    {
        std::lock_guard<std::mutex> lock(g_server_mutex);
        stop_server_locked();
    }
    {
        std::lock_guard<std::mutex> lock(g_alac_mutex);
        delete g_alac;
        g_alac = nullptr;
    }
    g_open_connections = 0;
    if (g_listener) {
        env->DeleteGlobalRef(g_listener);
        g_listener = nullptr;
    }
}

/* The shown PIN expired: the next pairing gets a new random one. */
void native_reset_pin(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_server_mutex);
    if (g_raop) raop_set_plist(g_raop, "pin", 0);
}

/* Drop every client connection (e.g. the TV was turned off); the server keeps listening. */
void native_disconnect(JNIEnv *, jclass) {
    if (g_open_connections > 0) restart_httpd(0);
}

jstring native_service_name(JNIEnv *env, jclass, jint service) {
    std::lock_guard<std::mutex> lock(g_server_mutex);
    if (!g_dnssd) return nullptr;
    return env->NewStringUTF(android_dnssd_service_name(g_dnssd, service));
}

jint native_service_port(JNIEnv *, jclass, jint service) {
    std::lock_guard<std::mutex> lock(g_server_mutex);
    if (!g_dnssd) return -1;
    return android_dnssd_service_port(g_dnssd, service);
}

jbyteArray native_service_txt(JNIEnv *env, jclass, jint service) {
    std::lock_guard<std::mutex> lock(g_server_mutex);
    if (!g_dnssd) return nullptr;
    int len = 0;
    const char *txt = service == DNSSD_SERVICE_RAOP ? dnssd_get_raop_txt(g_dnssd, &len)
                                                    : dnssd_get_airplay_txt(g_dnssd, &len);
    return to_jbytes(env, txt, len);
}

const JNINativeMethod kMethods[] = {
    {"nativeStart",
     "(Ldev/matinzd/airplaytv/airplay/AirPlayNative$Listener;Ljava/lang/String;[BLjava/lang/String;IIILjava/lang/String;ZZZ)I",
     reinterpret_cast<void *>(native_start)},
    {"nativeStop", "()V", reinterpret_cast<void *>(native_stop)},
    {"nativeDisconnect", "()V", reinterpret_cast<void *>(native_disconnect)},
    {"nativeResetPin", "()V", reinterpret_cast<void *>(native_reset_pin)},
    {"nativeServiceName", "(I)Ljava/lang/String;", reinterpret_cast<void *>(native_service_name)},
    {"nativeServicePort", "(I)I", reinterpret_cast<void *>(native_service_port)},
    {"nativeServiceTxt", "(I)[B", reinterpret_cast<void *>(native_service_txt)},
};

}  // namespace

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    pthread_key_create(&g_env_key, detach_thread);
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass(kNativeClass);
    if (!cls) return JNI_ERR;
    if (env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) {
        return JNI_ERR;
    }
    env->DeleteLocalRef(cls);
    return JNI_VERSION_1_6;
}
