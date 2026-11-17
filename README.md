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
3. Tap a game. **Select + Start** (or Back) opens the in-game menu. In menus, A selects and B goes back.

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

## Licence

NuonDroid's own code: choose one before publishing. NuanceResurrection's licence
(see its `License.txt`) restricts redistributing the *official distribution package*
modified and says it may not be redistributed for profit; its post-2020 contributions are
3-clause BSD. Check it covers how you intend to distribute the APK before putting one up.
