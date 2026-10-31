# 0.2.20

- **Amplitudeskalering (Normaliseret vs Følg volumen)**: Tilføjet indstilling til at vælge mellem intelligent dynamisk normalisering (AGC) og direkte lydstyrkevisning ("Følg volumen").
- **Støjtærskel & Stilhedssquelch (Bars til 0 ved pauser)**: Rettet fejl hvor Androids hardware `Visualizer.SCALING_MODE_NORMALIZED` forstærkede ubetydelig baggrundsstøj/dither i stille passager til fuld udslag (peak 127). Skiftet til `SCALING_MODE_AS_PLAYED` med software AGC og streng støjtærskel, så søjlerne falder rent til 0 under pauser og svage passager i stedet for at stå fast på fuld højde.
- **Signal-Skaleret Frekvenstilt & Equalizing**: Justeret frekvensspektret med en signal-afhængig akustisk pink-noise kompensation, så dyb bas, vokal og diskant har samme balancerede energiniveau uden at løfte støjbunden.
- **Global Broadcast**: `PARTY_AUDIO_FRAME` udsendes nu kontinuerligt under analyse, så både Music Screensaver, Party Mode og sangtekst-waveform altid modtager korrekte, dynamiske lyddata.

# 0.2.19

- **Musical Frequency Tilt & Equalization**: Integrated progressive +14 dB treble tilt across the spectrum bars to compensate for natural acoustic pink-noise rolloff. Highs, snares, and cymbals now dance actively alongside the kick bass instead of appearing flat or muted.
- **Home Assistant Telemetry & Entities Capability**: Added `entities` capability to manifest and published live diagnostic sensors `sensor.raspberry_raspberry_pi_4_spectrum_visualizer_telemetri` and `sensor.raspberry_raspberry_pi_4_spectrum_visualizer_lydsynkronisering` to Home Assistant for effortless observability of session ID, signal RMS, delay, and age.

# 0.2.18

- Clamped UPnP speaker delay to realistic 1500–4600ms range, preventing bogus multi-second buffer delay locks.

# 0.2.17

Automatic continuous Sonos UPnP buffer synchronization: remove the 20-second RelTime restriction to support continuous UGP radio streams (where Sonos RelTime counts indefinitely). Compute stream start from Android AudioTrack playback head position (`headSec`) so screensaver activation mid-song stays precisely anchored. Automatically re-measure and track buffer delay on track changes and periodic intervals without manual adjustment.

# 0.2.16

Fix "Plugin Manager: Unknown selection option" update error by retaining legacy `overlayTarget` options ("Kiosk Satellite + Fotoo", "Kiosk Satellite only", "Fotoo only") in the plugin manifest settings schema for backwards compatibility with previously saved values.

# 0.2.15

Cache spectrum gradients and rainbow colors; request hardware acceleration for external overlay windows and align invalidation with display frames. Preserve the Sonos delay queue through quiet passages while AudioTrack advances. Ignore sub-150ms locked delay jitter, matching Twinkly Bridge. Physical-device timing and GPU behavior still require verification.
