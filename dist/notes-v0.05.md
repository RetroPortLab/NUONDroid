Faster CPU mode.

- New: Settings > Performance > Faster CPU mode (on by default). The emulator now runs straight-line NUON code as pre-decoded blocks instead of one instruction at a time.
- This is the mode that caused the black screen in v0.01-v0.02. The cause is found and fixed: it miscounted a branch's delay slots when one of them was empty, ran one instruction too many, and the NUON eventually crashed. With the fix, the NUON test program passes every interpreter and compiler test in this mode.
- How much faster depends on the game's code: about 9% on the test program, possibly more in games with long processing loops.
- If a game misbehaves, turn Faster CPU mode off (it takes effect when you next start a game) and let me know which game.
