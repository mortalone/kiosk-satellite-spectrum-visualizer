## Spectrum Visualizer Overlay 0.2.2

- Fixes Digital / Sendspin mode on Android builds where no bars appeared.
- Captures waveform data from the active playback session and performs the FFT
  inside the plugin.
- Adds **Digital capture method**: Auto, Sendspin session, Device playback.
- Auto prefers the Sendspin session and retries against device playback if no
  frames arrive.
- Adds **Report digital source status** for diagnostics.
- Digital capture does not use microphone input.
- Keeps Classic Winamp, Level heat, Rainbow and Single color modes.
