# Spectrum Visualizer Overlay

Winamp-style spectrum overlay for Kiosk Satellite screensavers and Fotoo.

Install in **Kiosk Satellite → Plugin Manager → Add plugin** using:

`https://github.com/mortalone/kiosk-satellite-spectrum-visualizer`

## Source / capture

- **Animated** — decorative movement without audio input.
- **Microphone** — FFT of room audio using Android AudioRecord.
- **Digital / Sendspin** — Auto: prefer Kiosk Satellite's Sendspin AudioTrack; fall back to device playback if capture fails.
- **Sendspin - Session** — only the Sendspin playback session.
- **Device playback** — Android's digital output mix.

Digital modes never use microphone input. Music Assistant must send music to
the kiosk's Sendspin player, for example as a playback-group member.
AudioTrack discovery uses the application's bridge path, the live
EchoReference registry and activity object graph. Registry references are
read only: the plugin never consumes echo-cancellation samples or alters gain.

**Visualizer gain** controls microphone and digital FFT bar strength without
changing playback volume. Android capture uses normalized scaling, but on
some devices muting the output may return silence. This plugin does not yet
have a pre-volume PCM stream.

**Media player (optional)** only gates visibility. With **Only while playing**
enabled, that HA entity must be `playing`; it does not select audio input.
Leave the entity empty to remove that gate.

## Visibility

Use three separate fields:

1. **Visibility entity (optional)** — Home Assistant entity picker.
2. **Visibility condition** — Always, Active, Inactive, state/numeric comparison or Time between.
3. **Visibility value (optional)** — comparison state/number or range.

Examples:

| Entity | Condition | Value |
| --- | --- | --- |
| binary_sensor.motion | Active | (empty) |
| sensor.lux | Numeric below | 30 |
| sensor.temperature | Numeric between | 18..24 |
| media_player.stueetagen | State equals | playing |
| (empty) | Time between | 22:00-06:00 |

Always imposes no extra visibility rule. Screensaver selection and the
optional playing gate still apply. Time ranges may cross midnight.

## Refresh actions

Use **Refresh rate: 10 FPS (low CPU)**, **20 FPS (default)** or **30 FPS
(smooth)**. The action changes the running analyzer immediately, without
restarting audio capture. The chosen rate is saved across plugin and app
restarts. Other setting changes preserve it. The debug panel reports it.

## Colors and layout

Choose Classic Winamp, Level heat, Rainbow or Single color. Classic colors
segments by height; Level heat colors the whole bar by its current level.
Single, low, mid, high and peak-hold colors remain individually configurable.
Position, edge offset, width, height, opacity and bar count are configurable.

## Diagnostics

Digital mode shows a persistent debug panel at the top of the device screen.
It reports source/capture mode, FPS, gain, track discovery, session, waveform
peak/RMS, capture errors and visibility gates. A successful poll with peak=0
and rms=0 means Android returned silence.

**Report digital source status**, **Show persistent debug overlay** and
**Hide debug overlay** control diagnostics. Hide lasts until plugin restart.
**Show visualizer now / test** previews the spectrum; **Hide visualizer** ends
the preview. Diagnostics stay separate from visibility rules.

## Upgrading to 0.2.8

The host limit is still respected: 20 settings and 9 commands.
Existing source, gain, colors, layout and media-player gate are preserved.
Re-enter any old compact visibility rule using the three new fields; the
condition defaults to Always. Refresh defaults to 20 FPS until an action is
chosen. A previous forced capture method must be selected using the combined
source field; existing Digital / Sendspin now means Auto.

Microphone mode can compete with wake-word capture on some Android builds.

## 0.2.10: Party audio feed

While full-screen Party Mode has an effect selected, the existing analyzer
keeps running without its normal spectrum/debug overlay. It shares bounded
frames inside Kiosk Satellite with Now Playing 0.2.2. Source, gain and refresh
rate remain controlled here; Party controls its own visual style. When Party
closes, screensaver/visibility rules apply again. The lease is checked even
with debug switched off, so a crashed Party view cannot keep capture active
forever. Animated data is marked as demo.


Standalone Party Mode uses its own visibility. This companion yields while Party is active and resumes its normal visibility afterward. Spectrum Visualizer 0.2.11 uses Party’s gain and FPS during Party while retaining the chosen audio source.

### Diagnostics · 0.2.12

The top diagnostic panel is hidden by default. Press the **Show Debug** or
**Digital Status** plugin action when troubleshooting; **Hide Debug** removes it.
These actions can be placed as Kiosk buttons. Digital/Sendspin selection alone
no longer opens the panel.
