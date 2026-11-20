# Measurement notes

Three rates were tracked throughout the experiments.

| Measurement | What it represents | Source |
| --- | --- | --- |
| Active hardware mode | Timing selected for the physical DisplayPort output | DisplayPort driver mode-set output and SurfaceFlinger active mode |
| Render rate and pacesetter | The render policy for a display and the display supplying scheduler timing | SurfaceFlinger dump, including `Pacesetter Display`, `Follower Display`, and `renderRate` |
| App layer cadence | The updates recorded for a given layer during a workload | SurfaceFlinger TimeStats and the browser's own observations |

## Before and after the pacesetter change

| Same Settings scroll | Phone pacesetter | Monitor pacesetter |
| --- | ---: | ---: |
| External hardware mode | 1920×1080 at 240 Hz | 1920×1080 at 240 Hz |
| Monitor render rate | 240 Hz | 240 Hz |
| Scheduler timing | 120 Hz phone panel | 240 Hz monitor |
| Settings layer frames | 1,304 | 2,738 |
| TimeStats `averageFPS` | 124.904 | 248.841 |

The automated workload repeated an up/down scroll gesture on the same Settings layer. Counts are the recorded layer totals for each run. The TimeStats average derives from a histogram of whole-millisecond presentation intervals. Nominal 120 Hz and 240 Hz periods of 8.33 ms and 4.17 ms can fall in bins corresponding to 125 and 250 fps. The table shows the recorded values and the roughly twofold change in app updates, with the physical output independently verified at 240 Hz. Exact sustained app FPS would require a longer trace with presentation timestamps and the test duration. [AOSP TimeStats histogram and calculation](https://android.googlesource.com/platform/frameworks/native/+/17a9c62a2e14faabcd54387241c0c1b3e4896441/services/surfaceflinger/TimeStats/timestatsproto/TimeStatsHelper.cpp)

## Browser observations

- [Early 60 fps TestUFO screenshot](../evidence/screenshots/testufo-60fps.png) shows an unverified browser warning and an empty refresh-rate field. It documents browser behavior during the early tests.
- Samsung Internet's idle layer recorded TimeStats values of **54.524** and **56.655** before the Chromium change. The later capture recorded **1,447 layer frames** and a TimeStats estimate of **249.612** while the display refresh was 240 Hz. The same histogram caveat applies. Idle here means no pointer input with an animated page visible.
- [Later TestUFO screenshot](../evidence/screenshots/testufo-239fps.png) shows **239 fps** and a sync failure. The refresh-rate field remains empty. The independent display logs supply the hardware timing evidence.
- The browser change addressed the separate `ThrottleMainFrameTo60Hz` feature. Chrome and Brave were not verified with the configuration. [Chromium feature definition](https://chromium.googlesource.com/chromium/src/+/25a5bc7242a972e00204085eca1bc300b9861102/cc/base/features.h)

## Final refresh policy

The last device capture shows:

- Internal active mode 1440×3120 at **60 Hz**, with physical and render ranges **0–120 Hz** after releasing the stale pin. The range describes an allowed policy and does not measure the panel's minimum observed rate.
- External active mode 1920×1080 at **240 Hz**, with physical and render ranges **239.5–240.5 Hz** and the monitor selected as pacesetter. The range is a software tolerance around 240 Hz.
- In the simulated stale-connect test, SurfaceFlinger reported about **60 Hz** before the virtual replug and **240.00005 Hz** after it. The small decimal difference is how the software reported the nominal 240 Hz mode.

## Heat and battery

During the loaded session, Android reported thermal status **3 (severe)**, skin **44.3 °C**, and a battery sensor reading of **42.0 °C**. The Android battery service separately reported **41.9 °C**. CPU cores were approximately 58–62 °C. The phone was driving DeX while running Parsec and charging over USB. The shell snapshot recorded SurfaceFlinger at **90.6% CPU** under that tool's process accounting. There is no controlled thermal or battery comparison in the record. Restoring adaptive refresh makes lower phone-panel rates possible. It is not a measured battery-life improvement. [Android thermal mitigation](https://source.android.com/docs/core/power/thermal-mitigation)

## Reading the evidence

The [device excerpts](../evidence/logs/device-excerpts.txt) preserve selected tool output rows with their original event numbers. The earlier watcher logs are copied as recorded. The [early display excerpts](../evidence/logs/early-display-excerpts.txt) preserve refresh tokens and pacesetter fields from the larger diagnostic captures. The Android version labels and media captions in this archive explain how those historical captures were interpreted. The archive was verified offline and no device measurements were repeated while assembling it.

For a future display comparison, capture the physical mode, compositor pacesetter and layer-present timing with the same workload. Record the phone panel state, connection hardware, thermal status, and whether the browser was idle. These are needed to explain differences between runs. [Perfetto FrameTimeline documentation](https://perfetto.dev/docs/data-sources/frametimeline)
