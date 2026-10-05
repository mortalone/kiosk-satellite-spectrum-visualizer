# Spectrum Visualizer Overlay

Winamp-style spectrum overlay for Kiosk Satellite screensavers and Fotoo, with animated or microphone FFT modes.

Install in **Kiosk Satellite → Plugin Manager → Add plugin** using:

`https://github.com/mortalone/kiosk-satellite-spectrum-visualizer`

## Modes

- **Animated** — decorative Winamp-style spectrum.
- **Microphone** — real-time 1024-point FFT using Android AudioRecord.

An optional Home Assistant media player can gate the overlay so it is only visible while the player is `playing`.

Microphone mode is experimental because Voice Satellite / wake-word capture may compete for the same microphone on some Android builds.


## 0.2.0: digital Sendspin spectrum and color modes

The visualizer now has three sources:

- **Animated** — decorative movement without audio input.
- **Microphone** — FFT of room audio from Android AudioRecord.
- **Digital / Sendspin** — attaches Android's Visualizer effect directly to
  the AudioTrack used by Kiosk Satellite's native Sendspin player. This is the
  decoded digital stream, so room noise and speech are not part of the FFT.

For Digital / Sendspin mode, Music Assistant must actually send the music to
the Raspberry Pi's Kiosk Satellite Sendspin player (for example as a member of
the playback group). The Pi does not need to be your audible room speaker; the
plugin only needs Kiosk Satellite to have the active Sendspin AudioTrack.

### Color modes

- **Classic Winamp** — green at low bar height, yellow in the middle, red at
  high level. Quiet bars therefore remain green while louder bars grow into
  yellow/red.
- **Level heat** — colors the entire bar according to its current normalized
  dB level: low → mid → high.
- **Rainbow** — frequency-position rainbow.
- **Single color** — one configured hex color.

Low, mid, high, single and peak-hold colors are independently configurable with
hex values such as `#22C55E`, `#FACC15` and `#EF4444`.
