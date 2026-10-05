## Spectrum Visualizer Overlay 0.2.0

- Adds **Digital / Sendspin** source mode.
- Digital mode resolves Kiosk Satellite's live Sendspin AudioTrack and attaches
  Android's Visualizer FFT directly to its audio session.
- The digital mode analyzes decoded music, not microphone/room sound.
- Automatically reattaches if Kiosk Satellite recreates the AudioTrack between
  streams.
- Adds **Classic Winamp**, **Level heat**, **Rainbow**, and **Single color**
  color modes.
- Classic Winamp uses green → yellow → red vertically, so quiet bars stay
  green and loud bars reach red.
- Level heat changes the whole bar color by normalized dB level.
- Adds configurable low, mid, high, single and peak-hold hex colors.
- Animated and Microphone modes remain available.
