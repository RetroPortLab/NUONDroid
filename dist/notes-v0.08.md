Music (VLM): beat detection now works.

- VLM's beat detection never fired in v0.07. VLM gets beats from the player's CD-audio hardware, which NUONDroid bypasses, so the beat detector had no input. NUONDroid now feeds it from the music as it plays: kick drums and snares/hats, measured against their recent level.
- VLM's clock also ran about 100 times too slow in v0.07, which upset its tempo tracking and anything else it timed. Fixed: it now runs in real time.
- Tested with a 120 BPM test track: VLM detects every kick, measures the tempo at about 120 BPM, locks onto it and drives the beat-following effects. A steady drone with no drums gives no false beats.
- Still tested only in the desktop test rig with synthetic music, not yet on a handheld with real songs.
