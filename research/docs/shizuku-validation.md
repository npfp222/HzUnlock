# HzLock for DeX Shizuku validation

On November 19, 2026, the HzLock APK requested Samsung’s high-resolution modes through Shizuku running as ADB shell. Android listed 60, 120, and 240 Hz at 1080p for the Odyssey G40B. **The monitor still received 1080p at 60 Hz.** The original root-backed 240 Hz DeX demonstration and the APK’s clean wireless-debugging test are separate runs.

## Test setup

- U.S. Galaxy S24 Ultra **SM-S928U**, Snapdragon 8 Gen 3, One UI 9, Android 17, locked bootloader.
- Samsung Odyssey G40B connected through the Cable Matters 201376 USB-C DisplayPort hub and DisplayPort cable used for the original test.
- **HzLock for DeX v0.1**, package `local.hzlock.dex`, installed and verified over wireless ADB. The APK is signed with a debug certificate. Its SHA-256 is `9B08AB78AF2D98C6D303A100A96090ED03B775FF2F052561DFD2D9ED1881B753`.

## Isolating the APK

In the first test, tapping **Enable high-resolution modes** caused DeX to disconnect briefly and return. The APK showed 1080p at 60 Hz active with a faster mode available. ADB showed the APK service running as `shell` and `app_process / HzLock run` running as `root`. SurfaceFlinger described the monitor as the pacesetter in this session. The old process made this test unsuitable for attributing the timing change to the new APK.

The old RootTimer app had started automatically after boot. ADB found a `BOOT_COMPLETED` receiver for `com.roottimer.app`. Android rejected disabling the receiver individually from ADB shell, so RootTimer was temporarily disabled at the package level for user 0. ADB then showed the package disabled and no matching boot receiver. The already-running root HzLock process persisted until the owner rebooted the phone.

After reboot, a changed boot ID confirmed a fresh session. RootTimer was still disabled. The process list showed `shizuku_server` and `local.hzlock.dex:displays` running as `shell`, with the old root HzLock process absent. This is the clean test. RootTimer was last verified disabled at its conclusion.

## Clean wireless debugging result

| Observation | Result |
| --- | --- |
| Monitor input info, checked by the owner | **1920×1080 at 60 Hz** |
| APK and Android DisplayManager | **60 Hz active** |
| Android’s 1080p mode list | 60, 120, and 240 Hz |
| Android’s saved monitor preference | 1920×1080 at `240.00005` Hz, nominally 240 Hz |
| DeX refresh policy | `LowRefreshRateToken:DexController` present |
| SurfaceFlinger’s external follower | Reported `activeMode` and `renderRate` of 240 Hz |
| SurfaceFlinger frame timing | The internal screen was the pacesetter at the sampled 60 Hz. `forcedPacesetterDisplayId=nullopt`. |

The monitor’s input info resolves the conflicting software reports for this run. SurfaceFlinger’s 240 Hz software state did not establish a 240 Hz signal. Android already had a 240 Hz preference saved for the monitor, so the reconnect and post-toggle mode list do not establish an isolated before-and-after change from the high-resolution button. The observed outcome is that the button worked through shell-backed Shizuku and the signal stayed at 60 Hz.

## Original 240 Hz result

The original standalone `HzLock` run used an already-rooted process. Samsung’s high-resolution switch exposed the monitor’s timings. The vendor start-mode selection and a DisplayPort reconnect brought up 1080p at 240 Hz. HzLock pinned the external physical and render policy near the active rate, then selected the external display as SurfaceFlinger’s pacesetter. The monitor was already at 240 Hz before the pacesetter change. For the same Settings scroll, layer frame counts increased from **1,304 to 2,738** after that change. The original captures give the full chronology and measurement caveats. [Experiment history](experiment-history.md) · [Measurement notes](measurements.md) · [Original log excerpts](../evidence/logs/device-excerpts.txt)

## Current APK and remaining validation

- On **ADB shell (UID 2000)**, the APK can request Samsung’s high-resolution modes and display Android’s active and available modes. **Start HzLock** is disabled in this mode. The clean test verified Shizuku binding, the shell service, the high-resolution action and DeX reconnect, and the resulting 60 Hz monitor input.
- On **root-backed Shizuku (UID 0)**, **Start HzLock** uses the archived external display policy and requests the monitor as the timing source. The prototype gates this path to SM-S928U on Android 17. It uses the same lock file as the original HzLock to avoid two processes controlling the display and prompts for a physical reconnect when needed.
- `MainActivity.java` shows `Display.getMode()` and the highest refresh rate Android reports at the active resolution. `DisplayService.java` enforces the privilege split. `RefreshSession.java` owns the root-backed display session. Gradle compiles a packaged copy of the archived `src/HzLock.java` into the APK.
- The debug build, lint task, APK signature check, installation, and launcher registration were verified. Lint reported zero errors and 29 warnings. The root-backed **Start HzLock** and **Stop HzLock** paths still need an isolated on-device test with root already available and the standalone daemon absent.

For a future full-rate test, record the monitor’s input rate, the SurfaceFlinger pacesetter, and the measured app-layer cadence separately. This captures the signal, scheduler, and visible workload without treating any single software mode field as proof of all three.
