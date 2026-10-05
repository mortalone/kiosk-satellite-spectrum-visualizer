# Spectrum Visualizer Overlay

Winamp-style spectrum overlay for Kiosk Satellite screensavers and Fotoo, with animated or microphone FFT modes.

Install in **Kiosk Satellite → Plugin Manager → Add plugin** using:

`https://github.com/mortalone/kiosk-satellite-spectrum-visualizer`

## Modes

- **Animated** — decorative Winamp-style spectrum.
- **Microphone** — real-time 1024-point FFT using Android AudioRecord.

An optional Home Assistant media player can gate the overlay so it is only visible while the player is `playing`.

Microphone mode is experimental because Voice Satellite / wake-word capture may compete for the same microphone on some Android builds.
