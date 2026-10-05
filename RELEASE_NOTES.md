# Spectrum Visualizer Overlay 0.2.7

- Discover live playback AudioTracks through Kiosk Satellite's EchoReference track registry in addition to app and activity object graphs. This supports release builds where private app bridge-holder fields are unavailable.
- Read the registry without consuming echo-reference samples or changing playback.
- Show application class, app-path failure, echo source count and successful discovery location in the persistent debug panel.
- Cache live tracks and limit unresolved discovery to once per second; bound graph traversal.
- Add a regression check for live static and singleton-backed registries, preserving audio samples.

20 settings and 6 commands are unchanged. Digital audio capture still uses Android Visualizer, not the microphone. The new discovery path requires verification on the affected kiosk.
