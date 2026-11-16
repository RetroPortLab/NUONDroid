Fixes games booting to a black screen with no sound (reported with Tempest 3000).

- The emulated NUON was stalling at startup: the core's "dynamic compiler" setting has no ARM version, and turning it on (as v0.01 and v0.02 did) switched the core into a mode that stops the NUON processors running. It's now off, and the core ignores it on ARM.
- Games still run on the interpreter, so expect some to be slower than full speed.
