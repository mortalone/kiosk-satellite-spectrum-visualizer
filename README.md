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


## 0.2.2: improved Digital / Sendspin capture

Digital / Sendspin now uses Android waveform capture from the active playback
session and performs the FFT inside the plugin. This is more compatible than
depending on Android's own FFT callback.

Digital capture method:
- **Auto** — prefer the Sendspin session; if no frames arrive, retry against
  device playback.
- **Sendspin session** — only the Sendspin playback session.
- **Device playback** — capture the device's digital playback path.

None of these digital modes uses microphone input.

The plugin also includes **Report digital source status**, which reports the
discovered AudioTrack, session id and whether capture frames are arriving.


## 0.2.4: visibility rules without exceeding Kiosk Satellite's manifest limit

Kiosk Satellite allows at most 20 plugin settings. Visibility is therefore
configured as one compact rule instead of three separate settings.

Use **Visibility rule (optional)** with:

`entity|condition|value`

Examples:

- `binary_sensor.motion|Active`
- `binary_sensor.presence|Inactive`
- `sensor.lux|Numeric below|30`
- `sensor.temperature|Numeric between|18..24`
- `media_player.stueetagen|State equals|playing`
- `time|Time between|22:00-06:00`

Leave the field empty to always allow the visualizer.


## 0.2.5: polling Digital / Sendspin capture

Digital / Sendspin no longer depends on Android delivering Visualizer callback
events. The plugin actively polls the attached Sendspin AudioTrack using
`Visualizer.getWaveForm()` at the configured refresh rate and performs its own
FFT. Auto mode still falls back from the Sendspin session to Android's digital
output mix if polling fails.

This remains digital playback capture and does not use the microphone.


## 0.2.6: persistent digital diagnostics

Digital / Sendspin automatically shows a persistent, once-per-second debug
panel at the top of the device screen. It stays visible even when media-player
or visibility rules hide the spectrum. It reports the media-player state,
screensaver state, visibility decision, AudioTrack/session, poll result, frame
age, waveform peak/RMS and the latest attach/poll error. A successful poll with
peak=0 and rms=0 means Android returned silence.

**Media player (optional)** is only a visibility gate; it never chooses the
audio source. With **Only while playing** enabled, its HA state must be
`playing`. Leave it empty to remove that gate.

**Report digital source status** now enables the persistent panel and reports
to plugin status and the Remote Admin Overview tile. **Show persistent debug
overlay** and **Hide debug overlay** control the panel independently of the
spectrum. Hide lasts until plugin restart; digital mode shows diagnostics
again on restart. No new settings were added (20 settings, 6 commands).

Fixes the invalid `digital-source` status-tile key and oversized tile text
that caused the diagnostic command to fail and Kiosk Satellite to disable
the plugin. Each diagnostic output is isolated so a host output failure
cannot prevent the on-screen panel.

## 0.2.7: release-build AudioTrack discovery

The plugin additionally searches the host's live EchoReference track registry
and the active activity. It reads registry references only: it never calls
`Source.take()`, changes gains, or consumes the echo canceller's audio.
This avoids depending only on private application bridge-holder fields that
release shrinking may remove. Discovery is cached and bounded.

Debug now includes `app`, an application-path error if present, `echoSources`
and `foundAt`, so an unavailable track can be distinguished from an empty
registry. Hardware verification is still needed for the affected kiosk.
