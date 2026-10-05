## Spectrum Visualizer Overlay 0.2.5

- Fixes Digital / Sendspin showing no spectrum while Animated mode works.
- Replaces callback-only Android Visualizer capture with active `getWaveForm()` polling.
- Polls the live Sendspin AudioTrack at the configured refresh rate and performs the FFT in the plugin.
- Auto still falls back to the Android output mix if the Sendspin session cannot be polled.
- Adds poll result and sampling rate to **Report digital source status**.
- Uses normalized Visualizer scaling, suitable for music visualization.
- Keeps the manifest at the 20-setting Kiosk Satellite limit.
