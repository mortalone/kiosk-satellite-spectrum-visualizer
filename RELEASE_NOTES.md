Spectrum Visualizer 0.2.14

- Support granular screensaver selection in `overlayTarget` ("Screensaver visning"), allowing users to explicitly select between `Webside + HA Media (Familie Album & Nature Frame)`, `Kun Webside (Familie Album)`, `Kun Home Assistant Media (Nature Frame)`, `Alle screensavers (Kiosk + Fotoo)`, `Alle Kiosk screensavers (uden Fotoo)`, `Kun Fotoo`, or `Deaktiveret`.
- Automatically re-poll and synchronize `mediaEntity` and `visibilityEntity` states on screensaver activation and view transitions so playback state is never stale when entering screensavers.
- Retain full backward compatibility with legacy settings (`Kiosk Satellite + Fotoo`, `Kiosk Satellite only`, `Fotoo only`).

Spectrum Visualizer 0.2.13

- Add automatic digital UPnP/Sonos speaker buffer synchronization (`SpeakerSync`) and a bounded frame delay queue (`DelayedFrame`) so both the screensaver spectrum overlay and Party Mode fullscreen visualizations align with physical Sonos speaker output.
- Auto-discover all Sonos zone coordinators on the LAN via SSDP + `ZoneGroupTopology#GetZoneGroupState` without hardcoded speaker names or IPs; persist speaker selection by hardware `RINCON_...` UUID so speaker renames or IP changes never break sync.
- Measure the sub-second `<RelTime>` flip against Sendspin stream start (`T0`) and save verified Universal Group stream buffer delay (`~3980 ms`) for immediate mid-stream lock.
- Add speaker sync commands (`syncAuto`, `syncOff`, `syncMinus100`, `syncZero`, `syncPlus100`), `Digital / Sendspin (no speaker sync)` source option, and live sync status in `digitalStatus` / debug overlay.
