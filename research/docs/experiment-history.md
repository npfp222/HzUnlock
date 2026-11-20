# Experiment history

## November 1–2, 2026 · Establishing the baseline

- The first watcher reported a misleading 60 Hz result after the display disconnected before mode enumeration. The revised watcher waited for a stable connection before drawing a conclusion.
- The stable stock test showed 1920×1080 at 60 Hz, 1600×900 at 60 Hz, and 640×480 at 60/75 Hz. The 75 Hz low-resolution entry was real but the preferred-mode requests left the system at 60 Hz. The global request also produced a permission error.
- Samsung's `enableHighResolutionsForExternalDesktop` switch changed the driver-visible DeX state and exposed more timings. At that stage the existing HDMI path offered 1080p at 60 and 120 Hz. The `HiRes` prototype invoked this switch successfully with ADB shell privileges on the tested phone.
- The preferred-mode experiment stored 1080p/120 Hz in Android. The monitor stayed at 60 Hz while connected and came back at 60 Hz after the manual replug. That separated availability of a timing from selection at connection.
- SurfaceFlinger output showed the internal panel as pacesetter. The display logs recorded Samsung's `LowRefreshRateToken:DexController`. These were distinct from the driver's mode list.

## November 16–17 · Working 120 Hz and startup behavior

- Selecting the vendor start mode at external connection resolved the preferred-mode problem. The 120 Hz test log captured an early mismatch such that the external SurfaceFlinger mode was 120 Hz while DisplayManager still reported 60. The hardware mode and render policy had to agree.
- The Hz120 prototype used the hidden SurfaceControl display-mode API to assert physical and render ranges. The early workflow could apply the same policy to the phone display, which the final build later corrected.
- HzLock replaced the prototype with a single-instance background process. It periodically refreshed the high-resolution switch, monitored attached displays, learned a start mode, and reapplied external policy if Android changed it.
- One earlier reboot test demonstrated the display service restarting after temporary root returned. It still needed a monitor replug because the external display had already initialized. Other reboot attempts exposed separate problems involving the debugging connection, Android application startup, and the environment needed by the display helper. This was a device-specific result.

## November 18 · DisplayPort and the pacesetter

- The original path reached 120 Hz. The Cable Matters USB-C DisplayPort hub was tested for 240 Hz. During early failures the phone could negotiate DisplayPort Alt Mode without receiving the monitor hot-plug signal. A replacement DP cable preceded the first reliable 240 Hz mode enumeration. The history supports an issue on the original connection path. The failed part was never isolated definitively.
- The monitor first connected at 120 Hz with 240 Hz offered. HzLock saved `1920:1080:240` as the start mode and the next connection came up at 240 Hz. The external render policy and physical scan-out both read 240 Hz.
- The internal screen still drove the scheduler. Display timing and app timing were therefore different. Turning off the phone panel left it as pacesetter in the capture.
- The working solution uses the existing privileged SurfaceFlinger pacesetter control. The archived `Pace.java` contains the first reflective probe. The guessed Java interface class failed on this device. The final `HzLock.java` uses the working compositor interface. The display-side fix was applied at runtime.
- With the monitor still at 240 Hz, the same Settings scroll went from 1,304 to 2,738 recorded layer frames after the pacesetter switch.
- A later blackout occurred even during an explicit 1080p/60 Hz trial with successful link training and an HDCP handshake. The setup resumed with hub peripherals disconnected, followed by a verified return to 240 Hz. USB detach events were present. The observations indicate a connection or hub-state issue, and the record does not isolate the cause.

## November 18–19 · Idle applications, reconnects, and internal-panel cleanup

- Some Chromium-based browsers showed about 60 fps when idle while the external mode still read 240 Hz. Samsung Internet was measured before and after disabling its `ThrottleMainFrameTo60Hz` behavior. Chrome and Brave had related configuration prepared, with their results still unverified.
- HzLock gained a delayed driver-triggered reconnect. Waiting gave Samsung's high-resolution switch time to take effect. A simulated stale external connection recovered from 60 to 240 Hz and promoted the monitor again. The code bounds replug attempts and can ask for a physical replug. The final full cold-boot chain was not verified end to end.
- The early internal-panel pin stayed in SurfaceFlinger after the process was replaced. Toggling Motion Smoothness and locking/unlocking the phone did not clear it during the test. The final revision checks the internal display once and widens the stale pin. The recorded active internal mode dropped to 60 Hz while the external display remained a 240 Hz pacesetter.
- A connected Parsec and USB-charging workload reached Android thermal status 3. The experiment did not isolate the effect of refresh rate from the rest of that workload.

## Firmware and support discussion

The phone was the U.S. Snapdragon model. The captured Android properties identify Android 17 and One UI 9, and the recorded firmware identity was consistent on the demonstration day. Verified boot reported a locked bootloader. Temporary root was available at runtime and was cleared by reboot.

The GitHub project discussed in the Reddit thread was RiosWesley/SamTimer. Its README labeled its documented SM-S928B test One UI 8. The local session recorded a customized build on the U.S. SM-S928U, including a crash-loop guard and a post-root startup integration for HzLock. The record does not establish that the published APK would gain root on the U.S. test firmware. A full Shizuku-only version of the display solution was never demonstrated. Shizuku backed by ADB shell has a different permission level from the privileged HzLock process.

The original Reddit demo verified one working device and video path. The stock fallback observed on this setup was 1080p/60 Hz. An update can change both the availability of the privilege prerequisite and the vendor display interfaces. The session established no release date or universal compatibility rule.
