Music (VLM): the effects now react to your music properly.

- v0.07 and v0.08 gave VLM its music in the wrong form, so most effects looked flat, dark or stuck on one colour, and ignored the beat. VLM expects a frequency spectrum, a separate waveform, and a loudness reading for its automatic gain. NUONDroid now supplies all three, as the player's own CD hardware did.
- VLM's automatic gain now settles at a sensible level instead of turning the input almost off. That was the main reason the visuals ignored the music in v0.08.
- Oscilloscope-style effects now show the actual waveform instead of a flat line.
- The stray line along the bottom edge of the picture is gone.
- Checked in the desktop test rig with real songs (pop, jazz, drum & bass): all 152 effects draw, and VLM's beat detection follows the drums. It's still not tested on a handheld.
