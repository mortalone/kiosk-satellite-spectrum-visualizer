Spectrum Visualizer 0.2.13

- Add automatic digital UPnP/Sonos speaker buffer synchronization (`SpeakerSync`) and a bounded frame delay queue (`DelayedFrame`) so both the screensaver spectrum overlay and Party Mode fullscreen visualizations align with physical Sonos speaker output.
- Auto-discover all Sonos zone coordinators on the LAN via SSDP + `ZoneGroupTopology#GetZoneGroupState` without hardcoded speaker names or IPs; persist speaker selection by hardware `RINCON_...` UUID so speaker renames or IP changes never break sync.
- Measure the sub-second `<RelTime>` flip against Sendspin stream start (`T0`) and save verified Universal Group stream buffer delay (`~3980 ms`) for immediate mid-stream lock.
- Add speaker sync commands (`syncAuto`, `syncOff`, `syncMinus100`, `syncZero`, `syncPlus100`), `Digital / Sendspin (no speaker sync)` source option, and live sync status in `digitalStatus` / debug overlay.
