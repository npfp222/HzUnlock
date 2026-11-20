# 240 Hz Samsung DeX on a Galaxy S24 Ultra

> This folder documents the original root-based experiment that led to [HzUnlock](../README.md). The app itself is in [`app/`](../app).

This archive records the display work that produced **1920×1080 at 240 Hz** in wired Samsung DeX on a **U.S. Galaxy S24 Ultra (SM-S928U, Snapdragon 8 Gen 3)** running **One UI 9.0 / Android 17**. The external display was an **Odyssey G40B** connected through a **Cable Matters 201376 USB-C DisplayPort hub** and a replacement DisplayPort cable. The phone used Samsung's signed firmware and a locked bootloader. The tested HzLock process had temporary root available.

The decisive result came from changing **SurfaceFlinger's pacesetter** from the 120 Hz phone panel to the 240 Hz monitor. The external output was already at 240 Hz before that switch. The same automated Settings scroll produced **1,304 recorded frames before** and **2,738 after**.

| Where to look | Contents |
| --- | --- |
| [Experiment history](docs/experiment-history.md) | What was tried, why earlier attempts stalled, and what the working build changed |
| [Measurement notes](docs/measurements.md) | Hardware mode, render policy, pacesetter, layer timing, screenshots, and known limits |
| [Device output excerpts](evidence/logs/device-excerpts.txt) | Original selected output for 240 Hz mode selection, the pacesetter change, the phone-panel fix, and thermal status |
| [Early test logs](evidence/logs/) | 60 Hz baseline, high-resolution switch, failed preferred-mode request, 120 Hz tests, and selected compositor output |
| [Screenshots](evidence/screenshots/) | Original 60 fps and 239 fps TestUFO captures, plus the initial device-settings capture |
| [HzLock source](src/HzLock.java) and [original DEX](builds/hzlock.dex) | The final archived display helper and its original tested build |
| [Earlier Java prototypes](src/experiments/) and [DEX builds](builds/historical/) | HiRes, Hz120, the API probe and pacesetter prototype, and the earlier HzLock binary |
| [Historical scripts](scripts/historical/) | Early display tests. The three workstation watchers use a supplied ADB endpoint |
| [SHA-256 manifest](SHA256SUMS.txt) | Checksums of the archived files |

## What made 240 Hz work

1. **Expose the mode.** Samsung's external-desktop high-resolution switch removed the DeX timing filter in the DisplayPort path. The old HDMI route then offered 1080p at up to 120 Hz. The working DisplayPort route offered 1080p at 240 Hz.
2. **Select the mode on connection.** Android saved the preferred mode during the early experiment, but the reconnected monitor still came up at 60 Hz. On this build the external mode was effectively chosen at connection. HzLock learned the fastest offered timing at the current resolution and used Qualcomm's display start-mode property. On the successful DisplayPort run it saved 1080p/240 Hz after the first connection came up at 120 Hz. The next connection came up at 240 Hz.
3. **Hold the render policy.** DeX acquired a low-refresh token. HzLock applied desired mode specs around the external display's active refresh, for both physical and render policy. It checks about every 40 ms and rewrites when needed. The hardware scan-out has its own 4.17 ms period at 240 Hz.
4. **Switch the timing source.** The phone was still the 120 Hz pacesetter with the monitor already at 240 Hz. SurfaceFlinger's existing privileged pacesetter interface let the monitor drive the scheduler. This change nearly doubled the measured Settings layer cadence.

The final build also releases a stale 120 Hz pin left on the phone's own panel by earlier builds. The last capture shows the monitor actively at 240 Hz and the phone actively at 60 Hz with an allowed range of 0–120 Hz.

## Building the display helper

On Windows, use [build-hzlock.ps1](scripts/build-hzlock.ps1) with a JDK and a local Android SDK. The build produces a new DEX in the chosen output directory. The archived DEX remains the reference for the historical tests. The build uses Java 11 source and target settings, Android SDK platform 36, Android Build Tools 36.0.0, and D8 min API 34. The verification rebuild matched the original DEX byte for byte.

```powershell
.\scripts\build-hzlock.ps1 -SdkRoot 'C:\path\to\Android\Sdk'
```

HzLock runs on the tested Android firmware as a Java program through `app_process`. It expects an existing authorized privileged execution environment. It uses hidden Samsung and SurfaceFlinger interfaces whose signatures and behavior can vary by firmware. The build script compiles the display program and makes no device changes.

## Archive scope

The source, builds, captures, and notes in this folder cover the DeX display work. The archive excludes the temporary-root package and exploit tooling, the combined root restore and startup scripts, whole transcripts and firmware dumps, and private ADB endpoint values. The original project was a device-specific test. Compatibility with an unchanged third-party APK, other phones, or other firmware remains open.

Hardware and Android references are available from the manufacturers and AOSP. [Galaxy S24 Ultra specifications](https://news.samsung.com/global/enter-the-new-era-of-mobile-ai-with-samsung-galaxy-s24-series) ? [Odyssey G40B](https://www.samsung.com/us/monitors/gaming/25-inch-odyssey-g40b-fhd-ips-240hz-1ms-gsync-gaming-monitor-sku-ls25bg402enxgo/) ? [Cable Matters 201376](https://www.cablematters.com/pc-1537-179-foldable-usb-c-hub-with-displayport-14-and-100w-charging.aspx) ? [Android refresh policy](https://source.android.com/docs/core/graphics/multiple-refresh-rate) ? [Android VSync](https://source.android.com/docs/core/graphics/implement-vsync)

The public demonstration is linked here for context. [Samsung DeX running at 240 Hz](https://www.reddit.com/r/SamsungDex/comments/1xzyxha/samsung_dex_running_at_240_hz/)
