# HzUnlock

HzUnlock unlocks your monitor's full refresh rate in wired Samsung DeX. Out of the box, DeX keeps apps at 60 Hz. HzUnlock lets them run at up to the monitor's own rate (240 Hz on the tested setup), or any lower smooth rate you choose. It runs through **[Shizuku](https://shizuku.rikka.app/)** with Wireless debugging, so root is optional. There is no upper limit: the top rate is whatever the monitor connects at.

> HzUnlock is an independent project. It isn't affiliated with or endorsed by Samsung. It changes runtime display settings through hidden system interfaces. Turning it off, stopping Shizuku, or rebooting returns everything to Android's defaults.

## Install

1. Download the APK from [Releases](../../releases) and install it.
2. Install [Shizuku](https://shizuku.rikka.app/download/) and start it with **Wireless debugging** (or root).
3. Open HzUnlock and tap **Allow** in the Shizuku prompt.
4. Connect the monitor and turn on **High Refresh**.

## Compatibility

HzUnlock detects what each phone supports and uses only that. **Compatibility** in the app shows the result, and **Copy Report** copies it for bug reports, including the monitor's fastest rate at each resolution so connection limits show up.

| Level | What works | Needs |
| --- | --- | --- |
| **Full** | Apps at the monitor's full rate, plus every lower smooth rate | Samsung's pacesetter calls in `libgui.so`, and SurfaceFlinger reporting their state |
| **Partial** | Apps at up to the phone screen's fastest rate (often 120 Hz) and the lower smooth rates | Hidden `SurfaceControl` display-mode APIs |
| **Limited** | DeX's high-refresh mode unlock only | Samsung DeX |

**Verified:** Galaxy S24 Ultra (SM-S928U, Android 17 / One UI 9) with an Odyssey G40B at 1080p/240 Hz over a USB-C DisplayPort hub, at the Full level. Other Samsung phones, tablets, and foldables are expected to work at the level they report, but haven't been tested. Neither have rates above 240 Hz. Please open an issue with your Compatibility report.

High refresh also depends on the cable or hub. Some HDMI adapters stop at 120 Hz, and USB-C hubs that keep USB 3 data give DisplayPort only two lanes, which limits bandwidth (1440p at 144 Hz usually needs all four). The slider only offers rates the current connection really runs at.

## Features

- **One switch.** **High Refresh** unlocks DeX's high-refresh modes, lifts DeX's 60 Hz cap on apps, and sets the rate they run at. The session keeps running inside Shizuku after you close the app.
- **One slider.** Its stops are rates the system really runs at: the monitor's own rate, plus the phone panel's real modes that divide it evenly, so every frame stays up for the same number of monitor refreshes. The monitor's signal stays at its connected rate, and apps and animations run at the chosen rate.

  | Monitor | Slider stops |
  | --- | --- |
  | 360 Hz | 24 · 30 · 60 · 120 · 360 |
  | 240 Hz | 24 · 30 · 48 · 60 · 80 · 120 · 240 |
  | 144 Hz | 24 · 48 · 144 |
  | 120 Hz | 24 · 30 · 60 · 120 |
  | 165 Hz | 165 only (no even divisors) |
- **Quick Settings tile.** Toggle high refresh from the notification shade. The tile shows the live rate. Long-press it to open the app.
- **Launcher shortcut.** Long-press the app icon and choose **Toggle high refresh**.
- **Restore Automatically.** After Shizuku restarts, for example after a reboot, high refresh comes back the next time you open the app or its tile.
- **Cool Down When Hot** (off by default). When Android reports moderate heat, the rate drops to 120 Hz at most; at severe heat, to 60 Hz. It goes back up after 30 seconds of cooler readings.
- **Fix Slow Connections** (off by default). If the monitor comes up below its fastest mode, HzUnlock reconnects it once per plug-in. If it comes back at the same rate, HzUnlock stops trying and tells you the connection tops out there. **Reconnect Monitor** is also available as a button.
- **Connection hints.** When the monitor only offers 60 Hz over the current hub or cable, or reaches a faster rate at a lower resolution, HzUnlock says so.
- An iOS-style design that follows the system light/dark theme and fits phone screens and DeX windows.
- **Diagnostics.** A report you can read before sharing: the compatibility summary, a timestamped log of what the session did (monitors connecting, reconnects, rate changes, heat, errors), any saved crash of the app or its display service, and optionally the display-related lines of Android's log with e-mail addresses and long IDs masked. Copy it, share it, or open a prefilled GitHub issue. If HzUnlock crashed, it offers the report the next time you open it.
- **Update check** (on by default). Once a day HzUnlock asks GitHub whether a newer release is out and offers to download it. **Check for Updates** under About checks right away.

## How it works

The ADB shell UID holds `ACCESS_SURFACE_FLINGER`, which allows the calls below. Every hidden API is looked up at runtime, so a missing one turns its feature off instead of crashing.

- **Pacesetter.** Apps render on the vsync of SurfaceFlinger's pacesetter display. Samsung's `forcePacesetter` / `resetForcedPacesetter` calls choose it. Their numbers aren't hard-coded. `SurfaceFlingerCodes` reads the dynamic symbol table of `/system/lib64/libgui.so`, finds `BpSurfaceComposer::forcePacesetter(long)` and `::resetForcedPacesetter()`, and decodes the `MOVZ W1, #code` that each loads before `transact()`. The result is used only when each function has exactly one such constant, the two are adjacent, and SurfaceFlinger's scheduler dump has a `forcedPacesetterDisplayId` field. After every call HzUnlock reads that field back, and on any mismatch it stops using the calls and paces apps from the phone screen instead.
- **Rates.** At the monitor's rate, the monitor is the pacesetter. At any lower rate, and at every rate when pacesetter control is unavailable, the phone panel paces apps, held at a real mode with `SurfaceControl.setDesiredDisplayModeSpecs`. The policy is edited by setting fields on a freshly read copy, not through constructors that change between Android releases. On foldables, the panel that's switched on is used. SurfaceFlinger ignores policy changes on an external display after it connects, so HzUnlock doesn't touch the monitor's policy.
- **DeX.** `enableHighResolutionsForExternalDesktop` lifts DeX's timing filter when the build has it.
- **Heat.** `dumpsys thermalservice` provides the thermal status.

Root adds connect-time timing control (`vendor.display.hdmi_cfg_idx`) and a driver-level reconnect.

## Privacy

HzUnlock uses the network for one thing: the update check, a request to `api.github.com` for this repository's latest release. It sends no information about your phone, monitor, or usage beyond what any web request carries (your IP address and a `HzUnlock/<version>` user agent). Turn off **Check for Updates** in Settings and the app makes no network requests at all. Diagnostics reports stay on the phone until you copy or share them yourself.

## Repository layout

| Path | Contents |
| --- | --- |
| [`app/`](app) | The Android app (Gradle project) |
| [`app/src/main/java/io/github/npfp222/hzunlock/`](app/src/main/java/io/github/npfp222/hzunlock) | `DisplayControl` (hidden API lookups), `SurfaceFlingerCodes` (call-number discovery), `RefreshSession` (control loop), `DisplayService` (Shizuku service), `MainActivity`, `HzTileService` |
| [`research/`](research) | The original root-based 240 Hz DeX experiment: notes, measurements, logs, screenshots, and the `HzLock` prototype that preceded this app |

## Build

Requirements: JDK 17 or later, and the Android SDK with platform 36 (set `ANDROID_HOME`). The Gradle wrapper downloads Gradle 8.13.

```powershell
cd app
.\gradlew.bat assembleDebug lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/HzUnlock-debug.apk`.

### Signed release

Release builds are shrunk with R8 (`proguard-rules.pro` keeps the Shizuku service entry points). Create your own signing key once, and keep the keystore and its passwords private:

```powershell
keytool -genkeypair -v -keystore hzunlock-release.jks -alias hzunlock -keyalg RSA -keysize 4096 -validity 10000
```

Put the keystore in `app/`, and add `app/keystore.properties`. Git ignores both.

```properties
storeFile=hzunlock-release.jks
storePassword=…
keyAlias=hzunlock
keyPassword=…
```

```powershell
.\gradlew.bat assembleRelease
```

The signed APK is written to `app/build/outputs/apk/release/HzUnlock-release.apk`. Without `keystore.properties` the build is unsigned. HzUnlock uses hidden system interfaces, so it's distributed through GitHub Releases rather than Google Play.

## License

HzUnlock is **source-available, not open source**. You're welcome to read the code and build it yourself to verify the releases. Redistributing, republishing, or reusing it requires written permission. See [LICENSE](LICENSE) for the terms. Copyright © 2026 npfp222. All rights reserved.
