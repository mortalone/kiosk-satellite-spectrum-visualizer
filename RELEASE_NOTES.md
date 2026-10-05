# Spectrum Visualizer Overlay 0.2.8

- Restore three visibility settings: entity picker, condition selector and comparison value.
- Combine source and digital capture into one selector. Digital / Sendspin is Auto; Sendspin - Session and Device playback force the selected method.
- Rename Microphone gain to Visualizer gain: it already applies to both microphone and digital FFT levels, without changing playback volume.
- Move refresh rate to actions for 10, 20 and 30 FPS. Changes apply to the running analyzer without restarting capture and are saved across plugin/app restarts.
- Include refresh rate and visual gain in the debug panel.

Still within the host limit: 20 settings and 9 commands.

Upgrade: existing source, gain, colors, layout and media-player gate are preserved. Re-enter any old compact visibility rule in the three new fields; the condition defaults to Always. Refresh defaults to 20 FPS until an action is chosen. Any previous forced Digital capture method must be reselected using the combined source field.

Capture remains Android Visualizer in normalized mode. This update does not provide pre-volume PCM capture or guarantee visualization while the device is muted.
