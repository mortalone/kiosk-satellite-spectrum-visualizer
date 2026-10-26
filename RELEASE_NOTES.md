# 0.2.17

Automatic continuous Sonos UPnP buffer synchronization: remove the 20-second RelTime restriction to support continuous UGP radio streams (where Sonos RelTime counts indefinitely). Compute stream start from Android AudioTrack playback head position (`headSec`) so screensaver activation mid-song stays precisely anchored. Automatically re-measure and track buffer delay on track changes and periodic intervals without manual adjustment.

# 0.2.16

Fix "Plugin Manager: Unknown selection option" update error by retaining legacy `overlayTarget` options ("Kiosk Satellite + Fotoo", "Kiosk Satellite only", "Fotoo only") in the plugin manifest settings schema for backwards compatibility with previously saved values.

# 0.2.15

Cache spectrum gradients and rainbow colors; request hardware acceleration for external overlay windows and align invalidation with display frames. Preserve the Sonos delay queue through quiet passages while AudioTrack advances. Ignore sub-150ms locked delay jitter, matching Twinkly Bridge. Physical-device timing and GPU behavior still require verification.
