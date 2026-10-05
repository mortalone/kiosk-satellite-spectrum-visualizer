# Spectrum Visualizer 0.2.10

- Supply internal audio frames to full-screen Party effects in Now Playing 0.2.2, keeping one existing analyzer.
- Continue capture while normal spectrum/debug overlays remain hidden; use existing source, gain and FPS.
- Include bounded waveform samples and bands; mark animated data as demo.
- Restore normal visibility after Party exit. Check the presentation lease even when debug is disabled.
- Wait for the old analyzer thread to finish before restarting, avoiding concurrent captures.

Build and audio-discovery tests run in CI. Actual Android capture and Party visuals require kiosk testing.
