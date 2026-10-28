# ADR 0009: A/V sync: per-stream clocks and a shared presentation latency

Status: accepted (2026-10-18)

## Context

Lip sync took many iterations. Lessons learned:

- Audio and video timestamps can use different sender clocks (once ~9 h apart after
  an AirPlay video session). A shared offset flipped between them.
- Any latency estimate that includes what is queued in the AudioTrack feeds back on
  itself: inserted silence is queued, so the estimate grows and the latency runs
  away. This happened three times with different formulas.
- Holding decoded video frames fills the display queue and stalls the decoder.

## Decision

- Native layer: each stream (audio, video) maps sender timestamps (`ntp_time_remote`)
  to `System.nanoTime()` with its own offset. A stream reuses the other stream's
  offset when its timestamps fit it (lip sync), otherwise anchors on its own first
  packet's arrival. AirPlay music (ALAC) anchors on the sender's NTP time. A
  timestamp more than 5 s from now re-anchors that stream.
- `AvSync` keeps one presentation latency = max of what video and audio need, plus
  20 ms. Raised at once, lowered by a quarter of the excess per second. It may be
  negative while mirroring (the anchor packet arrived late).
- Video need = arrival lateness + decode time. One late frame is ignored (second
  highest value per 1 s window), and so is the first second after a decoder starts.
  Frames wait compressed and are decoded just in time, then released with
  `releaseOutputBuffer(index, timeNs)`.
- Audio need = arrival lateness + measured device latency + start buffer. Nothing
  that depends on the queue.
- Audio inserts silence when early, drops packets when late (45 ms mirroring,
  120 ms music), and flushes and resyncs when more than 150 ms late with audio
  queued.
- AirPlay music: fixed latency, start at 700 ms, margin 150 ms, never lowered
  during a session (steady beats low for music).
- Setting "Low-latency mirroring": every frame is decoded on arrival and shown as soon
  as it is decoded (no slot, no margin, no jitter window); audio keeps its own latency
  (sound may lag the picture). Measured on a Beko MStar TV: the shared latency sat
  at 72-220 ms with a jittery Mac stream, all of it waiting that this mode removes.
- When a stream has to be anchored on a different clock from the other (one stream
  restarted with new timestamps), the native layer reports it and video stops waiting
  for audio's need, as in low-latency mode, but keeps smooth scheduling. The relative
  timing of the two streams is unknown then, so waiting only added delay (260 ms seen).

## Consequences

Mirroring adds about 0-50 ms (video only) or about 160 ms with sound, most of it the
Streamer's ~210 ms HDMI audio path. Music plays smoothly at ~700 ms. In low-latency
mode the receiver adds only decode time (24-50 ms on MStar decoders) and the display.
