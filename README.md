# NUONDroid

<img src="art/icon.svg" width="96" align="right">

A standalone Android app for playing NUON (VM Labs) games, built around the
[NuanceResurrection](https://github.com/andkrau/NuanceResurrection) emulator core.
Targeting the Retroid handhelds (arm64).

NUONDroid doesn't fork the emulator. NuanceResurrection is a git submodule, built
unmodified apart from the patches in `patches/`, and a small native host
(`app/src/main/cpp/host.cpp`) runs it the way a libretro frontend would: it owns the
GL framebuffer the core draws into, shows it on screen, plays the core's audio through
AAudio and feeds it gamepad input. Upstream already ships an Android arm64/GLES3 build
of the core; this adds the app around it, with the same look and controls as
[JagDroid](https://github.com/RetroPortLab/jagdroid).

## Download and updates

Get the latest APK from the [Releases page](https://github.com/RetroPortLab/NUONDroid/releases)
(arm64, Android 8 or newer). NUONDroid can update itself:
*Settings > Updates > Check for updates* downloads the newest release and hands it to
Android's installer (the first time, Android asks you to allow NUONDroid to install
apps). It also checks on its own at most twice a day. Your settings are kept.

## Build

```bash
git clone --recurse-submodules https://github.com/RetroPortLab/NUONDroid.git
gradle :app:assembleRelease          # needs JDK 17+, Android SDK, NDK 27.2.12479018, CMake 3.22.1
```

Without the release key this signs with the debug key, which is fine for testing but
can't update an installed release. CI (`.github/workflows/build.yml`) does a build
check on every push.

## Releasing (same scheme as JagDroid)

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` (v0.02 -> 2, `0.02-experimental`).
2. Build with the release key: put `keystore.properties` (storeFile, storePassword,
   keyAlias, keyPassword) and the keystore in the repo root - both are gitignored -
   or set the `NUONDROID_KEYSTORE*` environment variables. Then `gradle :app:assembleRelease`.
3. Copy the APK to `dist/NUONDroid-v0.02.apk`, write `dist/notes-v0.02.md`, commit and push.
   `.github/workflows/release.yml` turns it into the v0.02 release, and the app offers it as an update.

Every release must be signed with the same key, or Android refuses the update. Keep the
keystore and its passwords somewhere safe outside the repo.

## Using it

1. Install the APK, open it, and allow **all files access** (the core opens games by
   plain file path, and ISOs are read in place rather than copied).
2. **Games folder** → pick the folder with your NUON games. `.iso`, `.chd`, `.mds`/`.mdf`, `.zip`,
   `.run`, `.cof`, `.nuon`, `.cd` and `.img` are listed.
3. Tap a game.

Display options (Settings, and the in-game menu): Scanlines, Screen (4:3 or stretch),
Upscaling (Soft, Sharp pixels, Smooth HD) and Show FPS. NUON games draw their picture
in software into a 2D framebuffer, so there is no 3D scene to re-render at a higher
resolution; upscaling is done with filters instead. The FPS overlay shows the screen
rate, the game's own frame rate (how often it flips to a new frame - the number that
drops when emulation is slow) and NUON speed in millions of instruction packets per second.

 **Select + Start** (or Back) opens the in-game menu. In menus, A selects and B goes back.

The three BIOS files (`bios.cof`, `minibios.cof`, `minibiosX.cof`) are the clean-room
ones from the upstream repo and are bundled into the APK at build time.

### Controls

| Handheld | NUON |
|---|---|
| A / B (bottom / right) | A / B |
| X (left) / Y (top) | C-Left / C-Up |
| L2 / R2 | C-Down / C-Right |
| Right stick | C-pad (all four C buttons) |
| D-pad / left stick | D-pad |
| L1 / R1 | L / R |
| Start / Select | Start / NUON button |
| Select + Start | In-game menu |

## Cast to TV

*Settings > TV > Cast to TV* (also in the in-game menu) opens Android's cast picker:
pick a Chromecast or Google TV. Android streams the picture and the sound.

*While casting*: **TV only** (default) moves the picture to the TV and darkens the
handheld's screen; it shows where it's casting, and Select + Start still opens the
menu there. **Both** leaves Android mirroring the handheld. TV only needs the cast to
appear to apps as a second display; if your device's casting only mirrors, you'll
get mirroring either way. A display plugged in over USB-C works the same way.

The picture moves between screens without restarting anything: the emulator keeps
one GL context and hands it from the handheld's view to the TV's.

Over a wireless cast, the TV picture is drawn at 480 lines (the NUON's own
resolution) instead of the TV's full resolution (*TV resolution* in Settings or the
menu: 480p, 720p, 1080p or the TV's own), and the frame rate is held at a
steady 30 fps: each drawn frame carries two 60 Hz fields, so games run at full speed.
Wireless cast streams are 30 fps at best, so this saves encoding work without
losing anything. A TV on a cable keeps 60 fps.

With TV only, on-screen messages (effect changes, track names) show on the TV. In
Music, every effect change is shown there briefly, including automatic ones.

## Music (VLM)

NUONDroid can play your music with **VLM-2**, the Virtual Light Machine Jeff Minter
wrote for the NUON DVD players - the real one, run from a player's own firmware, like
JagDroid's music mode runs the Jaguar CD BIOS. The firmware isn't included: you
install it once from the **Samsung DVD-N501 firmware update** (`nuon.zip`, or the
`NUON/NUON.CD` file inside it, BIOS 1.3.52 from 2001).

1. **Music** (on the library screen) → **Install player firmware** → pick `nuon.zip` or `NUON.CD`.
   NUONDroid takes the playback engine (`pe`) out of the update and keeps an unpacked copy.
2. **Choose music folder**. Anything Android can decode plays: MP3, FLAC, Ogg/Opus, M4A/AAC, WAV.
3. **Play all**, **Shuffle**, or pick a track.

### Spotify and other apps

**Music → Play along with another app** shows VLM for whatever another app is playing:
Spotify, YouTube Music, a radio app. Start the music there first. That app keeps
playing it; NUONDroid only listens, and R2 / L2 skip tracks in that app like headphone
buttons do.

Android asks for two things:
- **Microphone**: Android puts hearing other apps' audio under this permission.
  Nothing is recorded from the microphone.
- **Screen capture** ("start recording or casting?"): the only way Android lets an app
  hear other apps' sound in full quality. NUONDroid captures sound only, never the screen.

Without screen capture, or if the playing app blocks capture (some music apps do),
NUONDroid uses Android's Visualizer instead: 8-bit mono, read every 15 ms so it
forms an unbroken signal. VLM follows the beat about as well as with capture. It switches by itself when capture hears nothing but the Visualizer
hears music. Android allows one screen capture at a time, so starting a Chromecast cast
ends NUONDroid's capture; it switches to the Visualizer then too.

**Track names.** The track and artist show in the bottom-right corner: *Off*, *When
the track changes* (6 seconds, the default) or *Always*. Set it in Settings, in the
menu, or with L3 / R3. For your files they come from the tags (the file name if there
are none). For Spotify and other apps, Android only shares what's playing with apps
given **notification access**: Settings → Track names from Spotify → Allow, and turn
on NUONDroid. It reads no notifications, only the now-playing info that unlocks.
If Android says the setting is restricted (it does for apps installed from an APK):
Settings → Apps → NUONDroid → ⋮ (top right) → **Allow restricted settings**, then try
again. Or skip notification access: in Spotify, turn on **Device Broadcast Status**
(Settings, under Social), and NUONDroid picks up each new Spotify track from that.

| Button | VLM |
|---|---|
| A / B (or d-pad right / left) | Next / previous effect |
| X / Y | Random effect |
| R2 / L2 (or d-pad down / up) | Next / previous track |
| R1 / L1 | Effects change by themselves: on / off |

*Skip tracks with* (Settings or the menu) swaps the two pairs: R1 / L1 skip tracks and
R2 / L2 turn automatic effect changes on and off.
| L3 / R3 (stick clicks) | Track name: off / when the track changes / always |
| Select + Start, Back | Menu |

How it works: the core loads the player's `pe` like a program and calls into it the way
the player's own menu shell does - init, load the built-in effects (152), switch to the
audio player, EnterVLM, VLMChangeEffect - on MPE3, with VLM's renderer running on MPE2.
The music is decoded on the Android side and played through AAudio. In the player,
VLM's input comes from the CD-audio path; NUONDroid supplies the same from what has
just played:

- a 128-bin spectrum (VLM's band analysis), as |FFT|^1.5 to match the treble weighting
  VLM applies;
- the newest 64 stereo samples (the oscilloscope-style effects);
- about 240 reports a second of low-band and high-band rises plus the overall level,
  which drive VLM's own beat detection, tempo tracking and automatic gain.

VLM draws 175×119 frames (the player scaled them up too) at about 20 fps. Effects that
stay black with your music are skipped.

## Performance: read this first

NUON is an unusual 4-core VLIW machine, and this core is CPU-bound. On x86-64 it uses
a JIT (asmjit); **there is no ARM64 JIT**, so on a handheld it runs the interpreter
path. How fast that is on your device's CPU is the main open question - expect some
games to run slower than full speed, and treat the first runs as a benchmark. An ARM64
back end for the core is the real fix, and would need to be done upstream.

Pacing is wall-clock based inside the core, so a game that can't keep up runs in slow
motion rather than dropping frames.

## Layout

```
app/src/main/cpp/host.cpp      native libretro-style host (GL blit, scanlines, AAudio, input)
app/src/main/cpp/CMakeLists.txt  builds upstream's libretro core + the host
app/src/main/java/...          library, settings, updater (JagDroid design); emulator activity (own process)
art/icon.svg                   launcher icon source
dist/                          released APKs + notes (published by release.yml)
patches/                       fixes applied to the submodule at build time
external/NuanceResurrection    submodule (andkrau/NuanceResurrection)
```

Patches applied to the submodule at build time (the `applyNuancePatches` Gradle task,
idempotent):

- `0001` makes the core's scratch directory honour `$TMPDIR` instead of a hard-coded
  `/tmp`, which doesn't exist on Android. Without it CHD and ZIP-with-ISO files fail;
  plain ISOs don't need it.
- `0002` defines `struct retro_message` in upstream's trimmed `libretro.h`. Upstream
  commit f28138d (15 Nov 2026) started using it without defining it, which breaks every
  libretro build of the core, not just Android. Drop this patch once upstream fixes it.
- `0003` adds Alcohol 120% `.mds`/`.mdf` images. DVD-style images (2048-byte sectors,
  which is every commercial NUON disc) are read in place like an ISO; raw CD-style
  images (2352/2448-byte sectors) are converted to a temporary ISO first. Worth
  offering upstream.
- `0004` (removed in v0.05) forced the per-packet interpreter on builds without a JIT,
  as a stop-gap for the block-mode stall that `0006` now fixes.
- `0005` trims the interpreter's per-packet overhead without changing behaviour:
  stopped MPEs are no longer called every step, and the media-callback tick is
  advanced inline and only calls out when something is queued. Measured with
  callgrind on nuontest: 351 -> 221 host instructions per NUON packet.
- `0006` fixes the IL block mode ([DynamicCompiler] on builds with no native emitter,
  i.e. ARM and x86-64 without asmjit). That mode counts branch delay slots by their
  packet-end markers, but FetchSuperBlock left NOP delay-slot packets out of the
  block, so a taken branch exited with ecuSkipCounter at 1 and the packet after the
  delay slots ran before the jump - corrupting registers until the MPE jumped to
  garbage and hit the address-0 breakpoint (black screen, no sound). Those builds
  now always keep both delay-slot packets, as the existing IL-downgrade path does.
  nuontest passes all interpreter and compiler tests in block mode; ~9% fewer host
  instructions per packet than the interpreter on its short blocks. Worth reporting
  upstream. NUONDroid turns block mode on by default (Settings > Faster CPU mode).
- `0007` exports two counters for the FPS overlay: `nuance_video_flips` (bumped when
  the game points the main video channel at a new frame via VidChangeBase/VidConfig)
  and `nuance_packets_executed`.
- `0008` adds the VLM host (`src/vlmhost.cpp`): with `nuance_vlm_mode` set it runs a NUON
  player's playback engine on its own and drives VLM-2 through it. It also adds what that
  firmware needs and the HLE BIOS doesn't provide - comm-bus answers from the VDG, DMA
  controller and misc-IO system timers, the HAL memcpy, the video field counter, a
  real-time 90 kHz clock, and the "CD Beat" reports VLM's beat detection runs on.
  Nothing changes for games (everything is behind the flag).

## Licence

NuonDroid's own code: choose one before publishing. NuanceResurrection's licence
(see its `License.txt`) restricts redistributing the *official distribution package*
modified and says it may not be redistributed for profit; its post-2020 contributions are
3-clause BSD. Check it covers how you intend to distribute the APK before putting one up.
