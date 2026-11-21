# How the external monitor's mode is chosen and changed

Research from 2026-11-20 on the Galaxy S24 Ultra (SM-S928U, firmware S928USQS6EZJ1, Android 17 / One UI 9) with the Odyssey G40B on the Cable Matters USB-C DP hub, HzUnlock 1.1.0 running with High Refresh on. Everything here was done as ADB shell or offline. **Root was not used** in this round.

The SurfaceFlinger and HWC binaries were read from copies an earlier root session had left in `/data/local/tmp`: `sf.orig` (Nov 18) and `hwc/` (Nov 16). The firmware build is dated Oct 13 2026, so the copies match the running build.

Evidence labels used below:

- **[device]**: measured on the phone in this session.
- **[bin]**: disassembled from this firmware's own SurfaceFlinger binary, with symbols from its `.gnu_debugdata`.
- **[fw]**: read from this firmware's own `framework.jar` / `services.jar` bytecode (pulled from the phone, disassembled with `dexdump`).
- **[src]**: read from public source that matches this platform but is not guaranteed identical to Samsung's build: AOSP `frameworks/native` (android17-release), CalyxOS `platform_hardware_qcom-caf_sm8650_display` (android17), Samsung's SM8650 kernel modules (`github.com/samsung-sm8650/android_kernel_samsung_sm8650-modules`, `qcom/opensource/display-drivers/msm/dp`).

## Short answer

1. The mode SurfaceFlinger uses for the monitor is decided **at connect** by Samsung's DisplayPort driver and Qualcomm's HWC. DisplayConfig can change the hardware mode live, but SurfaceFlinger keeps using the connect mode until hotplug.
2. SurfaceFlinger on this firmware **drops every mode request for the external display**, from any client. Samsung added an unconditional check to `SurfaceFlinger::setDesiredMode`: if the HWC reports the display's connection type as External, it logs a warning and returns. No property or flag is involved, so shell can't turn it off. The check is also the reason HzUnlock works at all: it is why DeX's own 10–60 Hz policy never drops the monitor back to 60.
3. SurfaceFlinger reads the external display's active mode **only on hotplug**. The HWC sends a hotplug only on a real (or driver-level) reconnect. A reconnect rebuilds the HWC display from scratch and reselects the connect mode. So a mode set live through DisplayConfig is never seen by SF, and it is lost on the next reconnect.
4. The connect mode is, in order:
   1. `vendor.display.hdmi_cfg_idx`, if it is set (root only).
   2. The mode the kernel flags `PREFERRED`, which is normally the EDID's preferred timing.
   3. If no mode is flagged, the first mode in the list (largest resolution, then highest refresh).

   The DeX high-resolution switch only decides whether the kernel caps modes to 50–60 Hz first.
5. **No shell-only way to pick the connect mode was found.** Shell can reconnect the monitor (via the DeX switch) and can switch HWC's mode live (DisplayConfig), but not both in a way SF follows. With root, `hdmi_cfg_idx` plus a driver reconnect gives exact control, and HzUnlock's root path already does this. It has one dangerous edge, covered below.
6. **Measured (Q4):** with the hardware switched live to 120 Hz while SF models 240, apps keep receiving **240 Hz vsync** (4.167 ms). The real presents land on 8.33 ms boundaries. SF notices the mismatch but never adopts the new period: it keeps hardware vsync on and stays in period confirmation, which costs power.

## 1. Connect: kernel → HWC → SurfaceFlinger

### Samsung DP driver (kernel) [src]

`dp_display.c: secdp_check_resolution()` runs for every EDID mode.

- **DeX filter.** If `secdp_check_dex_mode()` is true, a mode survives only if it is in Samsung's DeX table and `DEX_REFRESH_MIN 50 ≤ refresh ≤ DEX_REFRESH_MAX 60` (`secdp.h`). DeX mode is true unless both `dex.setting_ui == 0` and `dex.curr == 0`. Otherwise (mirror / "no DeX restriction") every mode the link supports passes, down to `MIRROR_REFRESH_MIN 24` Hz.
- **PREFERRED flag.** The EDID preferred mode keeps `DRM_MODE_TYPE_PREFERRED`. The flag is stripped if the device tree lacks `secdp,prefer-res`.
  - Samsung adds `PREFERRED` to a faster mode at the *same* resolution only when the EDID preferred refresh is **below 60 Hz**, and only up to 120 Hz (`secdp_has_higher_refresh`, `PREFER_REFRESH_RATE_MIN 60`, `..._MAX 120`).
  - Modes ≥ 8K also get it (`secdp_check_uhd2`).
  - Nothing promotes a faster mode when the preferred timing is already 60 Hz.

### Qualcomm HWC [bin + src]

- `HWDeviceDRM` init (`hw_device_drm.cpp` ~line 736) sets `current_mode_index_` to the **first** mode with `DRM_MODE_TYPE_PREFERRED`. If there is none, it stays at `0`, the first mode in DRM's sorted list (preferred first, then largest area, then highest refresh).
- `DisplayPluggable::Init` then applies `GetOverrideConfig()`. That reads `vendor.display.hdmi_cfg_idx` (`HDMI_CONFIG_INDEX_PROP`).
  - The value is **not an index**. It is a `width:height:fps` string, e.g. `1920:1080:144` (`HWTVDRM::GetConfigIndex`).
  - It is re-read on every connect, so no reboot is needed.
  - Samsung's `libsdmutils.so` `Debug::GetExternalResolution` reads the property. In `libsdmcore.so`, `DisplayPluggable::Init` seeds the override index with `0`. Its `HWTVDRM::GetConfigIndex` call resolves into `libsdmdal.so`, which scans for the first exact `hdisplay`, `vdisplay`, integer `vrefresh` match. It leaves the seed at `0` if there is no match. `Init` applies a mode only if that index differs from the already-selected one.
  - **Mode 0 is the first enumerated mode.** The no-match case is a no-op when the default is already 0. DRM normally sorts preferred modes first, and the sampled G40B enumeration is consistent with that ordering. The test does not establish that index 0 is always preferred, or always the fastest. Never use an unlisted mode as an override.
  - The property parser expects three valid decimal fields. Malformed input can terminate the composer. Distinct timings with the same dimensions and rounded integer rate resolve to the first matching mode. The composer also has a 640×480 retry path if initializing a forced timing fails.

### SurfaceFlinger [src + device]

SF learns the mode list and active mode in the hotplug path (`processHotplugConnect` → `getDisplayModes` / active config, `SurfaceFlinger.cpp` ~4066). That is the only place it asks HWC for the external display's active mode.

### What this explains

| Report | Explanation |
| --- | --- |
| S25 + dual-mode monitor, 1080p mode: connects at 288 Hz, black | With DeX high-res on, the kernel's filter is off and the EDID preferred timing (or, with no preferred flag, the fastest mode) is 1080p 288. The hub/link/monitor can't actually drive it. |
| Same monitor, 4K mode: 100 Hz listed, connects at 60 | Consistent with a preferred 4K 60 timing. Samsung only promotes a faster refresh when preferred is below 60. Confirm with an EDID from the affected input. |
| Portable 144 Hz monitors on direct USB-C (S23, S25 Ultra): connect at 60 | Consistent with a preferred 60 Hz timing when the faster mode is listed. The affected inputs were not available for EDID verification. |
| High-res off → always ≤ 60 | Kernel DeX filter: 50–60 Hz only. |
| G40B here: connects at 240 | Its EDID preferred mode is 1080p 240. SF lists it as mode id 0 / HWC config 0. |

## 2. The DeX high-resolution switch [fw + src]

`IMultiTaskingBinder.enableHighResolutionsForExternalDesktop(boolean)` → `com.android.server.wm.DexController.enableHighResolutionsForExternalDesktop` → `updateDisplayPortStateIfNeeded(!mMirrorBuiltInDisplay, primaryExternalDesktop.type == TYPE_EXTERNAL)`.

That method computes a state:

- High-res **on**: `0`.
- High-res **off**: `(dexOn ? 0x10 : 0) | (externalDesktop ? 1 : 0)`, i.e. `16` or `17`.

It writes the state as a decimal string to `/sys/class/dp_sec/dex` on the IoThread. Going from 17 to 16, it first reads the node and, if it says `2` (changing), retries 2 s later.

The kernel's `dex_store` parses the value with `get_options`: `setting_ui = (v & 0xf0) >> 4`, `run = v & 0x0f`. It **reconnects the monitor** (`secdp_reconnect`) only if all of these hold:

- `run` changed (`curr != prev`).
- `curr == setting_ui`.
- `secdp_check_reconnect()` says the DeX max timing differs from the preferred (or mirror-max) timing.

So:

- High-res on (`0`) switches the filter off and reconnects into the EDID preferred mode.
- High-res off (`17`) re-enables the 50–60 Hz filter and reconnects at ≤ 60.
- Toggling off → on (HzUnlock's shell Reconnect) can produce a driver-level replug. If the preferred maximum and the DeX maximum are the same timing, the compare prevents the reconnect. A monitor that prefers 1080p60 can therefore stay connected throughout the toggle even with 144 Hz listed. The same limitation applies when root writes `17` then `0` directly.

The same switch is also reachable as a shell command: `MultiWindowShellCommand` has `toggleHighResolutionsForExternalDesktop` [fw]. The exact `cmd` entry point is not yet checked on the device. HzUnlock already reaches it via binder, so this changes nothing.

The `dex` node also accepts `ExtDispOff,1` / `ExtDispOff,0` (driver-level off/on, outside the filter and timing-compare path) and `SkipAdapterCheck,<0|1>` [src]. Debug builds may expose `dp_pref_skip`, `dp_pref_ratio`, and `dp_debug`. `dp_pref_skip` bypasses the driver's preferred-resolution check and leaves the EDID's DRM `PREFERRED` flag intact. These controls are system-owned. The debug nodes were absent from `/sys/class/dp_sec` on the tested firmware.

The global setting `external_display_resolution` (`Settings.Global.SEM_EXTERNAL_DISPLAY_RESOLUTION`, value `FHD` here) is only *defined* in `framework.jar`, and nothing in `services.jar` reads it [fw]. Its only user is DeX's own Screen resolution picker: Settings' `com.samsung.android.settings.desktop.ExternalDisplayScreenResolutionFragment`.

On a choice, the picker stores the key in that setting and then calls `IWindowManager.setForcedDisplaySize(displayId, w, h)`. That is the same logical-size override as `wm size`. **DeX's resolution picker never changes the monitor's mode**; it rescales the desktop inside whatever mode the monitor connected in. SystemUI, the launcher and `displayassistant` don't reference the setting or the high-res switch. (On One UI 9, DeX is built into `services.jar` and Settings rather than shipped as a separate app.)

## 3. Why SurfaceFlinger ignores external mode requests [device]

Test: pin the monitor's `DesiredDisplayModeSpecs` to 120 Hz as shell (`SurfaceControl.setDesiredDisplayModeSpecs`, probe `ModeProbe set`), with a 5 s `atrace gfx hal view aidl` capture.

- **External** (`4633127787861907461`): the call returns `true` and the spec is stored (`defaultMode=1`). The trace shows `setDesiredMode 4633127787861907461` (~8 µs) twice, and **nothing below it**: no nested `DisplayModeController::setDesiredMode`, no `HasDesiredMode` counter, no `PendingModeFps`, and no HWC call. SF's active mode stays 240.
- **Internal panel** as a control (`4630946315032985731`): the same call produces the nested slice, `HasDesiredMode 1`, `PendingModeFps 120`, then `ActiveModeFps 120`. Restoring gives `ActiveModeFps 60` and `HasDesiredMode 0`.

The external display *is* registered with the mode controller (it has a `RefreshRateSelector`, and its policy is stored and reported). So the early return sits between SF's `setDesiredMode` and the controller, and it is Samsung code. AOSP's debug log line in `DisplayModeController::setDesiredMode` is absent even for internal changes, but Samsung's guard logs its own warning (below).

Consequences:

- Render-rate-only changes (`DesiredModeAction::InitiateRenderRateSwitch`) go through the same path, so they are dropped too.
- SF debug transaction `1035` (`setActiveModeFromBackdoor`) also ends in `setDesiredDisplayModeSpecsInternal`. It returns "Operation not permitted" for shell anyway (earlier session).
- DeX's `LowRefreshRateToken` policy (10–60 Hz) is dropped by the same check. That is why the monitor keeps its connect mode under DeX, and why the only lever is the connect mode.

**The check itself [bin + device].** Disassembly of `android::SurfaceFlinger::setDesiredMode(DisplayModeRequest)` at `0x38a2dc` in `sf.orig`:

```
38a408  ldr  x0, [x19, #0x2130]        ; mCompositionEngine
38a414  blr  [vtbl+0x20]                ; ->getHwComposer()
38a424  blr  [vtbl+0x160]  (x1 = displayId)
38a428  cmp  w0, #1
38a42c  b.eq 0x38a700                   ; ALOGW("%s: Attempted to set desired mode for external display %lu"), return
38a45c  bl   DisplayModeController::setDesiredMode   ; only reached otherwise
```

Slot `0x160` of the `android::impl::HWComposer` vtable (in `.data.rel.ro` at `0x8e74a8`) is `HWComposer::getDisplayConnectionType(PhysicalDisplayId)`. `ui::DisplayConnectionType::External == 1`.

The check doesn't depend on DeX state or any property. It depends only on what the HWC reports for the display, and Qualcomm reports pluggable DP displays as External.

The warning is in logcat whenever something asks. Pinning the monitor to 120 Hz and restoring produced, at Warn level:

```
W SurfaceFlinger: setDesiredMode: Attempted to set desired mode for external display 4633127787861907461
```

It appeared twice, once for the pin and once for the restore toward DeX's 60 Hz cap. Earlier logcat filters missed it.

A sibling Samsung method, `SurfaceComposerAIDL::notifyHFRmode`, has the same kind of guard ("Attempt to notifyHFRmode for external display").

With root, the only way around this would be patching or hooking SurfaceFlinger. That rules it out for HzUnlock.

## 4. Making SF re-read the mode

- **Hotplug is the only re-read path** [src]. The Qualcomm HWC calls SF's hotplug callback only when a pluggable display is created or destroyed (a DP uevent).
- **DisplayConfig** `setActiveConfig` (shell-allowed, `vendor.qti.hardware.display.config.IDisplayConfig/default`, code 6, `EXTERNAL=2`) does `SetActiveDisplayConfig` plus `Refresh(0)` and nothing else. It sends no hotplug and no `onVsyncPeriodTimingChanged`; that callback only fires for SF-initiated changes (`SubmitActiveConfigChange`) [src]. Measured earlier: the HW switches live to 120 Hz (HW vsync ~8.33 ms), while SF keeps modeling 4.17 ms for 20+ s.
- `setDisplayStatus` (pause/resume/online/offline) only changes the power mode [src].
- SF backdoor `1037` injects a hotplug for the **primary** display only [src].
- A driver reconnect (DeX switch, `ExtDispOff`, physical replug) does make SF re-read, but it rebuilds the HWC display. The connect mode is reselected by rule 1 above, and a DisplayConfig choice made before it is gone.

**No re-read path without a reconnect was found.**

### What apps get after a live DisplayConfig switch (Q4) [device]

Test: DisplayConfig `setActiveConfig(EXTERNAL, 1)` (1080p 120) while SF had connected at 240, held for about 6 s, then back to config 0. Measured during the switch:

| What | Result |
| --- | --- |
| App vsync: `DisplayEventReceiver` in an `app_process` probe, 3 s | 718 events, mean 4.178 ms (239.3 Hz), `VsyncEventData.frameInterval` 4.167 ms. Baseline at 240 was the same: 4.171 ms. |
| Real presents: `dumpsys SurfaceFlinger --latency` on Parsec's SurfaceView, 61 frames | Every gap was an exact multiple of **8.333 ms**, never an odd multiple of 4.167 ms. The hardware really ran at 120. |
| SF vsync model (`--vsync`) | Still `vsyncRate=240`, `period = 4.17ms`. `hwVsyncState=Enabled`, `mMoreSamplesNeeded=1`, `mPeriodConfirmationInProgress=1`. SF is resyncing but never accepts the 8.33 ms period. |
| After restore to config 0 | `hwVsyncState=Disabled`, `mMoreSamplesNeeded=0`: settled. |

Note: `Choreographer` can't be used from `app_process` on this firmware. Samsung's `doFrame` calls `ActivityThread.getIdsController()` and crashes without an app process. A raw `DisplayEventReceiver` works.

So, in the exact-divisor case (HW at half SF's rate):

- Every real vsync is also a predicted one, so frames land on real vsyncs; no misplaced presents were seen.
- Apps are told the display runs at twice its real rate. An app able to render at full rate does twice the work for frames that are never shown.
- Hardware vsync stays enabled for as long as the mismatch lasts.

The exact-divisor result is evidence for a possible 288 → 144 rescue. The test started with a working 240 Hz picture and cannot establish whether the live switch restores a black screen.

## 5. Recommended HzUnlock design

### Shell (Shizuku)

The shell path keeps what works today and adds guardrails:

1. Keep the DeX high-res switch on and the monitor as the forced pacesetter. With the switch on, the monitor connects in its EDID preferred mode, and SF can't be talked out of it.
2. **Detect "connected above what works".** If the monitor connects at a rate above what the link actually carries (the 288 Hz black-screen case), shell has two exits:
   - Turn the high-res switch **off**. The kernel's DeX filter then reconnects at ≤ 60. This is safe and works today, but only at 60 Hz.
   - Switch HWC live with DisplayConfig to an **exact divisor** of SF's rate (288 → 144). This was measured at 240 → 120 (section 4). The picture runs at the divisor and presents land on real vsyncs, but apps keep 288 Hz vsync and hardware vsync stays on. It is acceptable as a rescue for a black screen, so offer it as an option and don't apply it silently.

     A config switch is lost on any reconnect, so HzUnlock would have to reapply it after each one. Even at an exact divisor, the known app-vsync mismatch persists. Keep this out of the app until the affected monitor confirms that the switch restores its picture. High Refresh off remains the shell recovery.
3. **"Connected at 60, faster listed" (EDID preferred 60):** shell cannot fix this. Show the mode list and say that a replug won't change it. The options are a lower resolution whose preferred timing is faster, or root.
4. Per-UID frame-rate overrides (SF backdoor `1039`) are gated the same way as `1035` (uid system or `HARDWARE_TEST`), so they are almost certainly denied to shell. That is not yet tried.

### Root

The root path can write `hdmi_cfg_idx` and cycle the display through `ExtDispOff`. The old `dex` 17 → 0 sequence can skip the very reconnect that would apply a new mode to a monitor that prefers 60 Hz. The app needs two safeguards:

1. **Never target `fastest()` automatically.** For the S25 dual-mode monitor the fastest listed timing is 288 Hz, which went black. Let the user pick the target mode. Require visual confirmation and return to DeX standard modes if the chosen timing fails or the user cannot confirm it. The previous connect mode can itself be black.
2. **Validate against the current monitor.** Write only a well-formed `W:H:fps` derived from its enumerated timing, with `fps` as the kernel's integer `vrefresh`. Keep the property set only for the targeted reconnect. A stale override might match and force an unintended timing on a different monitor.

Optional root tools for diagnosis: `dp_pref_skip`, and `dmesg` lines from the driver (`prefer timing found! …`, `0x.. setting_ui:.. run:..`, `not need reconnect`).

The working tree implements **Monitor Mode** for root-backed Shizuku. It rechecks the monitor's enumerated timings before cycling the driver, rejects ambiguous integer-rate matches, and writes the override after the driver disconnects. It clears the override after the selected monitor returns. The user has 20 seconds to keep an exact match. A failure or unconfirmed switch requests standard DeX modes. The override covers one connection, so a later replug returns to the monitor's normal startup timing.

## 6. Settled since the first draft

- `vendor.display.hdmi_cfg_idx` is labelled `u:object_r:vendor_display_prop:s0` and unset. `setprop` as shell fails ("Failed to set property"). **Root only**, as expected.
- Samsung's external-mode guard: found and confirmed (section 3).
- Q4: measured (section 4).
- DeX's resolution picker: logical size only (section 2).

## 7. Still open (root, only with the owner's OK)

- Compare Samsung's vendor HWC and SurfaceFlinger binaries with the public source. Completed offline in this follow-up.
- Validate an `ExtDispOff` mode switch on a rooted test device and collect kernel logs. Check 240 → 120 with a 120 Hz app-vsync and present cadence, then repeat on a monitor whose preferred mode and DeX maximum coincide. ADB shell could enumerate the loaded `msm_drm` module but could not pull its retail binary. The `ExtDispOff` branch is supported by the matching public driver source and remains untested on this build.

## Device state after the initial shell session

- Restored: the external and internal SF policies exactly as found, the DisplayConfig active config 0 (240 Hz), and the SF vsync model settled with hardware vsync off.
- Unchanged: HzUnlock on at 240 Hz, the monitor as the forced pacesetter, `local.hzlock.dex` stopped, and `hdmi_cfg_idx` unset.
- No props or settings were changed.
- This session's temp files were removed from `/data/local/tmp`. The earlier root session's files there (`sf.orig`, `hwc/`, etc.) were left alone.

## Follow-up on the open reports

### Physical unplug with the monitor forced as pacesetter [device]

The owner unplugged and reconnected the USB-C hub once on the same S24 Ultra and G40B. The installed HzUnlock 1.1.0 stayed on with shell-backed Shizuku. The boot ID and compositor, composer, and HzUnlock service PIDs stayed the same.

| Time (local) | Observation |
| --- | --- |
| 21:02:36.952–36.975 | Qualcomm HWC receives the DRM disconnect uevent and sends the disconnect hotplug for display 1. |
| 21:02:37.018 | SurfaceFlinger picks the internal panel as pacesetter, 43 ms after the HWC callback. HzUnlock logs the monitor as disconnected and returns timing to Android. |
| 21:02:48.344–48.405 | HWC receives the DRM connect uevent. SurfaceFlinger stays paced by the phone. HzUnlock enumerates 20 external modes at attach, with 1080p/240 active, and holds app timing with Android while DeX settles. |
| 21:02:51.705 | SurfaceFlinger switches the pacesetter back to the monitor. The final dump confirms 1080p/240, forced external pacesetter, HWC config 0, and hardware vsync settled. |

This validates the observed hotplug handoff on one Snapdragon S24 Ultra. The firmware's `Scheduler::unregisterDisplay` in `sf.orig` (0x529d68) clears the forced pacesetter when it removes that display. Its pacesetter binder task rejects a force request for a display that is absent. A physical unplug is unexpected, so the app can clear its override once the disappearance is visible. Here the compositor had already selected the internal panel. App-initiated reconnects can release the override before disconnect. The app log also records a later disconnect at 21:03:44, return at 21:03:57, and restored 240 Hz pacing at 21:04:00. The trigger for that second event was not established. The reported S25 Ultra hard freeze remains unverified on the affected hardware.

RootTimer was opened to identify its installed root path, then returned to disabled-user. The device stayed unrooted because that action runs a kernel privilege-escalation exploit. ADB shell could read `/sys/class/dp_sec/monitor_info`. `dmesg`, the DP connector EDID and status, and `dp_sec/bw_code` returned permission denied. `/sys/class/dp_sec` did not expose `dp_pref_skip` on this build. No fresh root mode switch was run. The final `vendor.display.hdmi_cfg_idx` was empty and the old root HzLock daemon was absent. Nothing was added to `/data/local/tmp`.

### Exynos and connection-path diagnostics

There is no Exynos reproduction in this test. The published Samsung DeX compatibility list establishes wired DeX support, with no external refresh-rate matrix. The S21 FE report exposed only 60 Hz on its current path. The S24 FE report needs a timing list. [Samsung DeX compatibility](https://www.samsung.com/us/support/answer/ANS10001972/)

- Ask for HzUnlock’s **Diagnostics → Copy Report** with High Refresh on and the monitor attached, plus the monitor's input-info resolution and refresh. Ask for the phone model code and firmware, monitor model and input, and exact hub/adapter and cable. The app FPS slider is a separate measurement.
- If a faster timing is listed at the active resolution, that timing already made it into Android's mode list. An active 60 Hz mode can come from connect-time selection, and enumeration alone cannot prove the faster timing produces a picture. An EDID from that same input helps confirm the preferred timing.
- If only 60 Hz is listed, compare the same phone and monitor over a direct USB-C display input or USB-C–to–DisplayPort cable, then try the same hub/cable/monitor with a source known to drive the desired timing. Keep the monitor input and settings comparable. A faster mode appearing only when bypassing the hub implicates the original path. If the control source exposes the mode on the original path while the phone does not, the phone's firmware, negotiated link, or DeX filtering needs investigation. A dock may share its high-speed lanes with USB 3, which changes the DisplayPort bandwidth available to the display. [VESA DisplayPort Alt Mode presentation](https://www.usb.org/document-library/vesa-displayporttm-alternate-mode-usb-cr)
- For the S24 FE, collect the lists for both reported connection paths and, if the monitor has such a preset, test an actual lower-resolution input timing. DeX's logical resolution menu by itself does not control the physical input timing on the tested Snapdragon firmware. For the S21 FE SM-G990E, prioritize bypassing the Revodok and comparing with another source on the same monitor input. The absence of the One UI 9 pacesetter call is separate from both phones' available timing lists.

### DeX_Mod follow-up [report]

The 4K144 preset briefly reports 1440p60, then settles at 2160p60 with 100 Hz listed. The 1080p288 preset briefly displays a picture and repeatedly goes black. The reported app slider shows 288, 120, 48 and 24 Hz. The exact APK and timing of Android display events are unknown. The 4K behavior fits the distinction between listed modes and the selected connect timing. The listed phone-panel pacing options are separate from the blackout.

The working tree holds the app-rate controls steady while Android reports a new connection or timing settling. If Android removes the monitor within 20 seconds of the high-refresh connection starting, HzUnlock holds automatic retries for that monitor. A second such removal while held requests standard DeX modes once. App-initiated reconnects and their settling period have separate handling, and a picture loss without a display removal is outside this detector. For the affected S25, collect the full Diagnostics report with event and system logs where available, the settled monitor input-info reading, and whether High Refresh off stabilizes the 1080p preset.
