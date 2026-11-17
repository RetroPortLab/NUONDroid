Faster emulation.

- The interpreter does about a third less work per NUON instruction (measured: 351 -> 221 host instructions per instruction packet). Processors the game has switched off are no longer polled, and disc-streaming bookkeeping only runs when there's data waiting.
- The emulator no longer waits for the screen's refresh between frames. That wait was time the NUON wasn't running, and could be a big slice of every frame.
- The emulation thread asks Android for high priority so it stays on a fast core.

Still experimental: games run on an interpreter (there's no ARM JIT yet), so heavy games like Tempest 3000 will still be below full speed. Setting the handheld's performance mode to its highest setting helps.
