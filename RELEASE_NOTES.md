# Spectrum Visualizer Overlay 0.2.6

- Fix Report digital source status disabling the plugin: valid status tile key and short summary.
- Add a persistent, once-per-second on-screen debug panel, shown automatically for Digital / Sendspin.
- Show player/visibility gates, screensaver state, track/session, polling, frame age, waveform peak/RMS and capture errors.
- Add Show persistent debug overlay and Hide debug overlay commands; Report digital source status also shows the panel.
- Explain that Media player only controls visibility, not the digital audio source.
- Keep 20 settings; add a build-time check for Kiosk Satellite's settings/commands limits.

Digital capture still uses Android playback data and no microphone. Device verification is required to diagnose the missing spectrum.
