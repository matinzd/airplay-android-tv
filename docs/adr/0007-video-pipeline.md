# ADR 0007: Video: MediaCodec low-latency decoder with keyframe cache, H.265 4K

Status: accepted (2026-10-18)

## Context

Mirroring is H.264 (or H.265 for 4K) in Annex-B form. Frames arrive before the
playback screen and its Surface exist (the TV is waking up), and the iPhone sends a
keyframe only at the start and on rotation.

## Decision

- `VideoDecoder` keeps every frame since the last keyframe (up to 48 MB) and replays
  them into the decoder when a Surface is attached.
- Prefer a decoder with `FEATURE_LowLatency` (`c2.mtk.avc.decoder.lowlatency`,
  `c2.mtk.hevc.decoder.lowlatency` on the Streamer). This cut the delay a lot.
- A new SPS (rotation) starts a fresh decoder. The old one is released first: a
  Surface can feed only one decoder.
- Advertise the display's own size (at most 1920x1080), or 3840x2160 when the display's
  active mode is 4K and the "4K mirroring" setting is on: an iPhone encoded 3840x2160
  H.265 for a 1080p TV, which only cost encode, Wi-Fi and decode time. Use the mode's
  physical size, not `getRealSize()`: Android can render at 1080p on a 4K panel (the
  Streamer reports `Override size: 1920x1080`). Feature bit 42 (screen multi-codec,
  H.265) is advertised when the setting is on and the device has an H.265 decoder, so
  turning the setting off still turns H.265 off.
- The playback window asks for minimal post-processing (HDMI ALLM / game mode).

## Consequences

- Rotation works without errors; 4K H.265 mirroring runs at about 60 fps.
- HEVC decoders get VPS+SPS+PPS together as `csd-0`.
