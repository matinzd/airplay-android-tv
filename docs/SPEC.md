# AirPlay receiver for Android TV: specification

Target device: Google TV Streamer 4K (Android 14, `armeabi-v7a` userspace, MediaTek SoC).
Senders: iPhone, iPad and Mac (iOS/macOS AirPlay 2 clients).

The reasons behind each behaviour are in [`docs/adr/`](adr/README.md).

## 1. Discovery

- The receiver advertises `_airplay._tcp` and `_raop._tcp` on port 7000 (legacy ports:
  TCP 7100/7000/7001, UDP 6000/6001/7011).
- Name: the "AirPlay name" setting, or the TV's device name (`Settings.Global.DEVICE_NAME`).
- Device ID: a random, locally administered MAC, stored once in SharedPreferences.
  The pairing key is stored in `files/airplay-key.pem`. Both keep senders' pairings valid.
- The TV stays in the AirPlay list in standby.

## 2. Wake and session life cycle

| Event | Behaviour |
|---|---|
| Sender starts mirroring or music (RTSP SETUP) | Screen on, `PlaybackActivity` opens; HDMI-CEC turns the TV on |
| Sender starts AirPlay video (`/play`) | Same |
| New device needs a PIN | Same, the code is shown |
| Music starts or resumes from the phone | Same (brings the screen back after Back) |
| TV/Streamer goes to standby (`ACTION_SCREEN_OFF`) | All client connections dropped; server keeps listening |
| Last connection closes | Screen closes after 1.5 s (grace period for handovers) |
| No client feedback for 15 s | Server restarts its HTTP listener (client lost) |
| Boot / app update | Service starts (`BootReceiver`); `KeepAliveJob` checks every 15 min |

Required one-time device setup: `scripts/setup-device.sh` (display over other apps,
battery-optimisation exemption, notifications).

## 3. Screen mirroring

- Codecs: H.264, or H.265 when "4K mirroring" is on and the sender supports it.
  Advertised display: 3840x2160 (4K on) or 1920x1080, 60 fps.
- Decoder: the low-latency MediaTek decoder when available.
- Frames since the last keyframe are cached and replayed when the screen attaches.
- Rotation (new SPS) restarts the decoder cleanly.
- Picture is letterboxed to the video's aspect ratio on black. When the mirrored screen is
  narrower than 3:2 (a phone held upright, an iPad), the side bars show a blurred, dimmed
  copy of the picture (`PixelCopy` of a 48 px wide frame, up to 30 times a second; Android 12+).
- The window requests minimal post-processing (ALLM / game mode).

## 4. Audio

| Stream | Codec | Path |
|---|---|---|
| Mirroring | AAC-ELD (ct=8), 44.1 kHz, 480 samples/frame | MediaCodec `c2.android.aac.decoder` → AudioTrack |
| AirPlay music | ALAC (ct=2), 44.1 kHz, 352 samples/frame | Native ALAC decoder → AudioTrack |
| Other | PCM (ct=1), AAC-LC (ct=4) | Supported, rarely used |

Volume: AirPlay dB in -30..0 (-144 = mute) → linear gain `10^(dB/20)`; -30 and below = 0.
The TV remote sets the TV/receiver volume separately (ADR 0017).

## 5. A/V sync

See ADR 0009 for the full design. Key numbers:

| Constant | Value |
|---|---|
| Sync margin (mirroring) | 20 ms |
| Latency decrease | quarter of the excess per second, at least 10 ms |
| Latency cap | 1 s |
| Music start latency / margin | 700 ms / 150 ms, never lowered in a session |
| Audio late threshold (drop) | 45 ms mirroring, 120 ms music |
| Audio resync (flush) | > 150 ms late with audio queued |
| Audio early threshold (insert silence) | 15 ms; > 4 s early is treated as a bad timestamp |
| Default device latency (before measured) | 200 ms (Streamer HDMI ≈ 210 ms) |
| Re-anchor a stream's clock | timestamp > 5 s from now |
| Video: ignore after decoder start | 1 s |
| Low-latency mirroring setting | video is decoded and shown at once; audio keeps its own latency |
| Audio and video on different clocks | video ignores audio's need (lip sync is unknown) |

Expected added delay: mirroring 0-50 ms without sound, about 160 ms with sound; music about 700 ms.

## 6. AirPlay video (HLS, e.g. YouTube)

- UxPlay serves the playlists on `http://localhost:7000/master.m3u8`; Media3 ExoPlayer plays them.
- Phone controls: play, pause (`/rate`), seek (`/scrub`), stop; position is reported to the phone.
- `/playback-info` reports "still playing" until the video has ended or failed.
- Closing the video on the phone returns to mirroring if mirroring was on.
- If playback fails, the player stops, the phone is told the video has finished, and
  the TV shows a message for 10 s (Back closes it). Protected (DRM) video such as
  Apple TV+ plays its clear intro, then fails: the message says to use the Apple TV
  app on the TV. Other errors say "This video cannot play".

## 7. Music screen

- Cover art (full) on a blurred copy of itself; placeholder icon until art arrives.
- Status: "Playing" (accent) or "Paused" (dim) plus "from <device>".
- Title and artist: one line each, scrolling (marquee) when too long; album ends with "…".
- Progress bar with elapsed and total time (from the sender's `progress` updates,
  extrapolated while playing).
- Paused: cover dims with a pause sign; no audio for 1.2 s = paused.
- The last cover stays while paused (pausing stops the audio stream).
- On-screen controls (previous, play/pause, next): shown for 4 s after a key press
  (the pressed one bounces), always shown while paused.
- OLED protection: content drifts up to ±48/±32 dp every 60 s; screen dims to 35 %
  after 5 min without a key press or track change.

## 8. TV remote

| Key | Music screen | Mirroring / video |
|---|---|---|
| OK, Enter, play/pause | play/pause (DACP `playpause`) | — |
| Left / Right, media previous/next | `previtem` / `nextitem` | — |
| Media play / pause | `play` / `pause` | — |
| Back | pause (DACP) and close the screen | disconnect the sender |
| Home | leave it running | leave it running |

Opening the app during a session returns to the session; the home screen shows a
focused "Return to AirPlay" button while a session is active.

## 9. PIN pairing (optional)

- Off by default ("Require a code for new devices").
- A new device gets a random 4-digit code, shown full screen; it stays valid until a
  device pairs or 60 s pass.
- Paired devices are remembered by public key and reconnect without a code.
- "Remembered devices" in Settings forgets all of them.

## 10. Settings

| Setting | Default | Effect |
|---|---|---|
| AirPlay name | TV device name | Name in the AirPlay list |
| Require a code for new devices | Off | PIN pairing (legacy pairing, feature bit 27, `pw=true`) |
| 4K mirroring | On | Feature bit 42, 3840x2160 display |
| Low-latency mirroring | Off | Video is shown as soon as it is decoded; no lip sync |
| Remembered devices | — | Select to forget all |

Any change restarts the receiver and re-announces it.

## 11. Screens

- Home (`MainActivity`): status dot and text, AirPlay name, 3 setup steps, buttons
  (Return to AirPlay when active, Settings), IP and port, a warning if waking isn't set up.
- Settings (`SettingsActivity`): full-width rows with the current value on the right.
- Playback (`PlaybackActivity`): pairing code, connecting (icon + spinner), music,
  video, or a video error.

## 12. Not supported

FairPlay-protected video (Apple TV+, Netflix), AirPlay 2 multi-room audio (HomePod sync),
HomeKit. See ADR 0016.
