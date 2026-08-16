# debterm

A Debian terminal for Android. No launcher, no buttons, no settings: the whole
app is a text grid and a `bash --login` inside a real Debian bookworm rootfs.

- Debian arm64 (`debian:bookworm-slim`) bundled in the APK, unpacked on first launch
- real pty (`/dev/ptmx` + `fork`/`execve` via JNI), so `vim`, `htop`, `less`, job
  control and `apt` all behave
- `proot` for the chroot, no root and no user namespaces needed
- own VT/xterm emulator + `Canvas` renderer: scrollback, alt screen, 256 colour,
  truecolour, UTF-8

Built for **arm64-v8a only** and `targetSdk 28` on purpose — Android 10+ refuses
to `exec()` files in an app's data directory when targetSdk is 29 or higher,
which would kill every binary in the rootfs. Termux pins 28 for the same reason.

## Install

Grab `debterm.apk` from the latest [release](../../releases/latest) and install
it (allow unknown sources). First launch unpacks ~30k files; after that it opens
straight into a shell. `apt update && apt install ...` works out of the box.

## Keys

Phone keyboards have no Ctrl, Esc or arrows, so the volume keys are modifiers.
Tapped alone they still change the volume.

| chord | sends |
| --- | --- |
| Vol-Down + *key* | Ctrl+*key* (`^C`, `^D`, `^Z`, `^L`, `^A`, `^R`, ...) |
| Vol-Up + `e` / `t` | Esc / Tab |
| Vol-Up + `w a s d` | up left down right |
| Vol-Up + `p` / `n` | PgUp / PgDn |
| Vol-Up + `h` / `f` | Home / End |
| Vol-Up + `i` / `x` | Insert / Delete |
| Vol-Up + `1`..`9` `0` | F1..F10 |
| Vol-Up + `l` `u` `r` `q` | <code>&#124;</code> `_` `~` `` ` `` |
| Vol-Up + anything else | Alt+*key* |

Drag to scroll the scrollback, pinch to change the font size, long-press to
paste, tap to bring the keyboard back.

## Build

CI does everything (`.github/workflows/build.yml`): pulls `proot`, its ptrace
loader and `libtalloc` from the Termux apt repo into `jniLibs` as `lib*.so`
(that directory is the only exec-able one at runtime), compiles `pty.c` with NDK
clang, exports the Debian rootfs with `docker export`, then
`./gradlew :app:assembleRelease` and publishes a release.

The APK is signed with the debug key, so it installs but does not update over a
differently-signed copy.
