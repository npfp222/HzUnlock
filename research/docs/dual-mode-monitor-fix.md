# Start Mode: choosing the monitor's mode at connect without root

Status: implemented in the working tree on 2026-11-21 (uncommitted, build and lint pass). App line
references below are to 1.1.0 (`HEAD`).

**Device check (S24 Ultra + G40B, ADB shell, session code run from the debug APK via `app_process`):**
- Monitor Mode → 1080p120: won every race, set 1.9–5.9 ms after the composer saw the monitor; SF read
  120 as the initial mode; apps 119.3 Hz right after Keep and 120.0 Hz (8.333 ms) later, paced by the monitor.
- Kept start mode re-applied on a manual reconnect and on DeX's re-add (2.9 and 2.0 ms).
- Use Default → next reconnect back at 240. Not keeping within 20 s → High Refresh off, standard modes.
- **New finding:** DeX disconnects and re-adds the monitor ~8 s after its high-res off/on reconnect, with
  or without a start mode (plain reconnect too). The first build rolled back on that drop; the session now
  waits up to 10 s for the re-add during the Keep prompt, and the racer sets the mode again.
- Not tested: replug path, 288 Hz rescue, lost-race handling, other phones.

## Implementation (working tree)

| Piece | Where |
|---|---|
| DisplayConfig HAL client (codes 1/4/5/6/7, plausibility checks, fingerprint) | `DisplayConfig.java` |
| Racer thread: 1 ms polling of `isDisplayConnected` while a connection is likely (just armed, 30 s after a disconnect, or a USB-C partner attached per `IUsbManager`), 25 ms otherwise (4 ms if USB state is unreadable); full config scan + `setActiveConfig` on each new connection whose fastest mode matches the target's fingerprint | `StartModeRacer.java` |
| Monitor Mode without root: `switchMode` → racer armed → DeX high-res off 5 s / on → judged 1.5 s after the composer connection → Keep within 20 s, else High Refresh off. If DeX's reconnect doesn't reconnect the monitor, asks for a replug (60 s). One retry after a lost race. | `RefreshSession.beginStartSwitch`, `reconnectForStart`, `checkStartSwitch` |
| Kept start mode: saved to `/data/local/tmp/hzunlock-start-mode.txt`, armed before High Refresh's own unlock, re-applied on every matching connection; a lost race is put back to SurfaceFlinger's mode unless the start mode divides it evenly | `armKeptStart`, `checkKeptStart`, `forgetStartMode` (AIDL 10, "Use Default") |
| Rescue: a connection ≥ 200 Hz lost within 20 s gets its half rate as a pending start mode (waits 8 s for DeX to re-add, then reconnects); HzUnlock opens itself for the Keep prompt | `rescue`, `showApp` |
| A start mode the monitor drops at is forgotten | `forgetStartIfLost` |
| Fixes: connect-time NullPointerException (null display info mid-hotplug); brightness spam dropped from the report's system log, SDM composer lines kept | `DisplayControl.sfInfo`, `LogFilter` |

Root keeps its `hdmi_cfg_idx` path; the racer is used when root's pieces aren't there.

## Summary

- **The problem has two forms.** Some monitors start in a mode that is too fast for the link
  (DeX_Mod: 1080p @ 288 goes black in a loop). Many more start in a mode that is too slow
  (Lethyzs, audiophile-disorder, Caroloux, Lasm998, and DeX_Mod in 4K mode). Without root,
  HzUnlock can't change either: a reconnect always returns to the monitor's preferred mode.
- **A shell-only fix exists, and it was tested on the S24 Ultra.** Qualcomm's DisplayConfig HAL
  (`vendor.qti.hardware.display.config.IDisplayConfig/default`) is open to the shell user.
  - The composer creates an external display about 6–17 ms before it tells SurfaceFlinger.
  - A `setActiveConfig` call inside that gap makes SurfaceFlinger read the new mode as the
    display's *initial* mode. The first modeset is then already the chosen mode, and app vsync,
    SF's vsync model and the pacesetter are all correct.
  - The race was won twice out of two on the S24 + G40B (240 → 120, set 14–15 ms ahead of SF).
  - This does what root's `vendor.display.hdmi_cfg_idx` does, but needs only Shizuku.
- **The recommendation is a "Start Mode" thread** in the display service. It waits for a
  connection and sets the chosen mode inside that gap.
  - It is **automatic** for rescue: a monitor that keeps dropping at its own mode gets an exact
    half-rate start mode, such as 288 → 144.
  - It is **opt-in** for speed-ups: a monitor that starts at 60 Hz but lists 144 Hz.
  - Both cases use the same 20-second "Keep this mode?" confirmation. Without a yes, HzUnlock
    falls back to DeX's standard modes, so a black monitor always recovers.
- **This is Snapdragon only.** Exynos phones don't have DisplayConfig, so they keep today's
  behaviour.

## Evidence

### Why the 288 Hz mode goes black (likely cause; not confirmed on the S25)

These findings come from the S24 (SM8650) kernel DP driver in `src/kmod/.../msm/dp`:

- `secdp.h:62` defines `SECDP_MAX_HBR2`. The link tops out at HBR2: 4 lanes × 5.4 Gbps, which is
  **17.28 Gbps** of payload. SM8750 (S25) source wasn't available, so this cap is unconfirmed
  there.
- With `SECDP_OPTIMAL_LINK_RATE`, `secdp_gen_link_clk()` (`dp_panel.c:1907`) picks RBR, HBR or
  HBR2 from the highest pixel clock among all EDID modes, once per connection. A later mode
  change keeps that link rate.
- `dp_display_validate_link_clock` accepts any mode with `clock × bpp ≤ lanes × rate × 8`, with
  no margin. `dp_panel_get_supported_bpp` lowers bpp in steps of 6, down to 18, until the mode
  fits. A mode can be listed at exactly 100% of the link, or only at 6-bit colour.
- Samsung's poor-connection logic counts unstable link-status IRQ_HPDs (`dp_link.c:1409`).
  - Above 9 it disables DeX and runs `secdp_poor_disconnect_work`.
  - `secdp_link_status_work` treats more than 4 updates as poor.
  - The count drops by one every 2 minutes.
  - A link that trains but can't hold its margin produces exactly "brief picture, black, retry,
    black".

Estimated link use at 1080p. Each estimate is reduced-blanking × 24 bpp. The 240 Hz row is
measured on the G40B (`dumpsys SurfaceFlinger`: `h_total 2120, v_total 1112, clk 565220`).

| Mode | Pixel clock | Gbps (24 bpp) | Share of HBR2 |
|---|---|---|---|
| 1080p @ 288 | ~717 MHz | ~17.2 | **~99.6%** (or 6-bit) |
| 1080p @ 280 | ~700 MHz | ~16.8 | ~97% |
| 1080p @ 240 | 565 MHz (G40B) | 13.6 | 78.5% (works on the S24) |
| 1080p @ 200 | ~470 MHz | ~11.3 | ~65% |
| 1080p @ 144 | ~330 MHz | ~7.9 | ~46% |
| 2160p @ 100 | ~890 MHz | ~21.4 | doesn't fit; listed only at 18 bpp (~16 Gbps, 93%) |
| 2160p @ 60 | 533 MHz | 12.8 | 74% |

This also explains DeX_Mod's 4K mode. 4K @ 144 can't fit HBR2, so only 4K @ 100 at 6-bit is
listed, and the monitor's preferred 4K @ 60 is what the phone starts in.

### Why reconnecting can't help today

- SurfaceFlinger reads the composer's active mode once, at hotplug (`loadDisplayModes`, which
  logs `External display <id> initial mode`). The composer starts every connection in the EDID
  preferred mode.
- Samsung's guard in `setDesiredMode` (0x38a2dc) blocks mode requests on external displays from
  SurfaceFlinger and DisplayManager, including DeX's own. See `monitor-mode-control.md`.
- The DeX resolution picker only changes DeX's logical size. Caroloux confirms this: after
  choosing 1080p in DeX settings the slider stayed at 60 and the app still reported 4K.
- On a monitor whose preferred mode is DeX's standard 60 Hz, the high-res toggle often doesn't
  hotplug at all. In Lethyzs' log, most manual reconnects show no disconnect.

### The connect-time gap

From the Qualcomm composer source (`src/hwc/composer`):

- `HWCSession::HandleConnectedDisplays` creates the pluggable display under `locker_`, then runs
  `WaitForResources`, then `callbacks_.Hotplug`.
- During that time DisplayConfig already sees the display and SurfaceFlinger doesn't.

DisplayConfig AIDL transaction codes, in method order. These are stable AIDL, so the codes are
fixed.

| Code | Method | Arguments | Reply after status |
|---|---|---|---|
| 1 | `isDisplayConnected` | dpy | bool. Silent when there's no display. |
| 4 | `getConfigCount` | dpy | int |
| 5 | `getActiveConfig` | dpy | int. Logs a warning on every call while unplugged, so don't poll it. |
| 6 | `setActiveConfig` | dpy, idx | — |
| 7 | `getDisplayAttributes` | idx, dpy | non-null 1, size, vsyncPeriod, xRes, yRes, xDpi, yDpi, panelType, isYuv |

In every call, `dpy` is EXTERNAL = 2.

### Device runs (S24 Ultra, One UI 9, G40B 1080p 240 over a USB-C → DP hub, shell UID 2000)

The probe is an in-process `app_process` loop, at
`C:\Users\npepo\AppData\Local\Temp\claude\392582c82b491c04\5fb235e1-acfa-4cf4-a3a4-11aa21adfdf9\scratchpad\probe\Dc.java`.

**Race 1 (23:23)**

| Time | Event |
|---|---|
| 21.867 | `SDM: Create pluggable` |
| 21.878 | Created |
| 21.881 | Our `setActiveConfig(2, 1)` lands: `HWTVDRM::SetDisplayAttributes … FPS: 120 … CLK: 297000KHZ` |
| 21.889 | `Notify hotplug` |
| 21.895 | `SurfaceFlinger: External display … initial mode: {{id=1 … vsyncRate=120.00 Hz}}` |

Afterwards:
- App vsync measured 8.333 ms (120.0 Hz) with `DisplayEventReceiver`.
- `mDisplayModePtr id=1 120 Hz`, `hwVsyncState=Disabled`, `mMoreSamplesNeeded=0`.
- The forced pacesetter returned to the monitor.

**Race 2** gave the same result: the set landed 15 ms before SF added the display, at 120.0 Hz.

**Shell `service call` loop (startmode.sh, first version):** lost. Each call takes about 34 ms.
The set landed 25 ms after SF read 240, which is an ordinary live switch. A live switch leaves
SF's vsync model at the old rate (see the Q4 measurement in `monitor-mode-control.md`). **A win
needs an in-process loop.**

**Restore:** after each run a plain reconnect returned the monitor to 240. The final state was:
- `sfMode id=0 1920x1080@240`, app vsync 239.8 Hz, `hwVsyncState=Disabled`
- forced pacesetter on the monitor
- `hdmi_cfg_idx` empty
- temp files removed

**Side observation:** HzUnlock 1.1.0 re-enabled high-res about 1.5 s after the probe turned it
off, and started its own reconnect about 13 s later. Start Mode must tell `needsReconnect` and
`LinkDropTracker` that a slower start mode is intended, or the two will fight.

## What the new reports show

| Who | Phone | Monitor / path | What happens | Cause | Start Mode target |
|---|---|---|---|---|---|
| DeX_Mod | S25 (SM-S931W) | UPERFECT ZX27BU (`HG670T49`), direct USB-C, UserMode 1080p | Comes up at 288, brief picture, black, retry, repeating | 288 at ~100% of HBR2 (or 6-bit). Samsung poor-connection loop. | **1080p @ 144** automatic rescue; 240 or 200 opt-in |
| DeX_Mod | same | UserMode 4K | Starts at 1440p60, then 2160p60. App says 100 Hz; slider tops at 60. | Preferred 2160p60. 2160p100 only fits at 6-bit. | 2160p @ 100 (6-bit) or 1440p @ 120/144 if listed (needs a report in this mode) |
| Lethyzs | S23 (SM-S911B) | UPERFECT 144 Hz portable, direct | Always 1920x1080 @ 60; lists 60/120/144; reconnects never help | **Preferred mode is 60.** Bandwidth is ample (~46%). | **1080p @ 144** opt-in |
| Lethyzs | S23 | Acer XF240H via HDMI → USB-C adapter | Native 120, works | Preferred mode is already the fastest | none |
| audiophile-disorder | S25 Ultra | portable 144 Hz, direct | "Connected at 60, reconnect to reach 144", loops | Preferred 60, same as Lethyzs | 144 opt-in |
| Caroloux | S25 | 1080p 120 monitor via ROG 65W dock; TV 4K60 / 1080p120 | 60 on both; "switch DeX to 1080p" did nothing | Preferred 60 (monitor) / 4K60 (TV) | 1080p @ 120 (TV: a resolution change, untested) |
| Lasm998 | ? | Odyssey G5 via Revodok: DP 1440p60, HDMI 4K24 | 60 / 24 | Preferred 60. The HDMI side is a 4K EDID through an HDMI 1.4-class hub. | 1440p @ 120/144 over DP |
| Advanced_Common_7218, mfpedroom | S24 FE, S21 FE (Exynos) | — | 60 | No DisplayConfig on Exynos | not available |

So the **"preferred mode is too slow" case is the common one**: five reports compared with one
"too fast". Start Mode fixes both.

### Problems in 1.1.0 these reports expose

1. **Wrong advice for lower resolutions.** It says: "Your monitor can do %d Hz at %d × %d. Lower
   DeX's screen resolution to use it." This is `MainActivity.connectionAdvice`, HEAD line ~803.
   DeX's resolution setting doesn't change the monitor mode (Caroloux, and our own tests). Remove
   it, or replace it with Start Mode for that mode.
2. **The "tops out" message blames the hub or cable** (HEAD line ~793) for monitors on a direct
   cable whose preferred mode is 60. When the fastest mode fits the link with room to spare, the
   cause is almost always the preferred mode. Say so, and offer Start Mode.
3. **Manual Reconnect stays on offer** after the app has concluded "tops out". In Lethyzs' log it
   was pressed 12 times in 20 minutes with no change. Once the conclusion is reached, hide it or
   change it to "Start at 144 Hz".
4. **The Copy Report system log is mostly brightness spam.** Lethyzs' paste is 20 KB, and its
   system log section is entirely `SurfaceFlinger: setDisplayBrightness` and
   `LocalDisplayAdapter: surface lcd`, cut off by pastebin. Filter those two, and put SDM
   create/notify/destroy lines and SF `initial mode` lines first.
5. **A connect-time NullPointerException**, "Display update failed: NullPointerException …
   getClass() on a null object reference", appears twice. It happens at connect while SF briefly
   has the display without an active mode; the probe saw the same in `sfActive`. It is harmless
   but alarming. Treat a null active mode as "settling".
6. **Stops look odd.** DeX_Mod sees `[24, 30, 48, 60, 80, 120, 288]` (phone rates plus the
   monitor). Lower stops are paced by the phone panel, so 144, 100 and so on can't appear. The
   UI should label the slider an app frame-rate cap and the lower stops "paced by the phone
   screen". The monitor's own mode belongs to Start Mode.

## Design

### Fallback chain

| # | Step | Automatic? | When it applies |
|---|---|---|---|
| 1 | **Start Mode, won**: the monitor starts in the chosen mode | Rescue: automatic after evidence. Speed-up: opt-in. Both confirmed within 20 s. | Snapdragon, DisplayConfig reachable, target listed |
| 2 | **Start Mode, lost** (SF read the preferred mode first) | Automatic | Rescue: switch live to the exact divisor (288 → 144), then try again on the next hotplug. Speed-up: stay at the preferred mode, try again on the next hotplug, and never switch upward live. |
| 3 | The monitor's own menu: UserMode, or Standard / Switch 1 / Switch 2 | User | It may change the preferred timing. The UPERFECT "Switch 2" mode is said to add modes, 120 Hz among them. |
| 4 | DeX's standard modes (high-res off) | Automatic when unconfirmed | Always works, at ≤ 60 Hz |
| 5 | Physical: replug, or the monitor's 4K UserMode | User | Always |

Why the divisor in step 2: after a live switch SF keeps the old vsync model. When the new rate
is an exact divisor (144 of 288), frames still line up with every other vsync. The picture is
right and apps keep drawing at 288, so this is a rescue, not an end state. A non-divisor live
mode (240 of 288) would judder, so it is never used.

### When the thread is armed

The thread polls only while all of these are true:

- HzUnlock is on.
- The phone has DisplayConfig, checked once with a code 1 call.
- A Start Mode target exists.
- No external display is present.
- And one of these:
  - the USB-C port reports a partner. `IUsbManager.getPorts()` / `getPortStatus()`; shell holds
    `MANAGE_USB`, and `dumpsys usb` already shows `connected=true` as shell.
  - it is within 20 s of an external display being removed, which covers DeX_Mod's retry loop.
  - HzUnlock's own reconnect is running.

While armed it polls `isDisplayConnected` (code 1) every 1 ms. Code 1 doesn't log when nothing
is connected. Each poll is one binder call of about 0.1 ms in-process, compared with 34 ms
through `service call`. Disarmed, it costs nothing.

### Matching the target on a new connection

A monitor's display id can be the same in both UserModes. Its mode list can also differ by one
entry between connections: Lethyzs saw 10 and 11. So the thread matches by mode, not by index or
count:

1. Read the config count (code 4), then read attributes (code 7) for each config until the target
   `width × height @ hz` is found. Allow ±0.5 Hz. If two configs match, take the first: the G40B
   lists 120 Hz three times.
2. The saved target also records the **fingerprint of the fastest mode** seen on that connection
   (for example, `1920x1080@288`). If the connection's fastest mode differs, the monitor is
   another one or in another UserMode: do nothing.
3. Check the values are plausible (vsync 2–50 ms, sizes 320–8192). Otherwise disable Start Mode
   for the session and log why.

Reading 30 configs in-process takes about 3 ms, well inside the gap. To save time, the thread
first checks the remembered index with one code 7 call.

### Code sketch: DisplayConfig helper

```java
/** Qualcomm DisplayConfig AIDL, as the shell user. Codes are the stable AIDL method order. */
final class DisplayConfig {
    static final String SERVICE = "vendor.qti.hardware.display.config.IDisplayConfig/default";
    static final int EXTERNAL = 2;
    private static final int IS_CONNECTED = 1, CONFIG_COUNT = 4, ACTIVE = 5, SET_ACTIVE = 6, ATTRS = 7;

    record Config(int index, int width, int height, int vsyncNs) {
        float hz() { return 1e9f / vsyncNs; }
        boolean is(int w, int h, float rate) { return width == w && height == h && Math.abs(hz() - rate) < 0.5f; }
    }

    private final IBinder binder;
    private final String descriptor;

    static DisplayConfig open() {
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("checkService", String.class).invoke(null, SERVICE);
            return b == null ? null : new DisplayConfig(b, b.getInterfaceDescriptor());
        } catch (Exception e) {
            return null; // Exynos, or a ROM without the service
        }
    }

    boolean connected() throws RemoteException { return call(IS_CONNECTED, EXTERNAL) == 1; }
    int count() throws RemoteException { return call(CONFIG_COUNT, EXTERNAL); }
    int active() throws RemoteException { return call(ACTIVE, EXTERNAL); }
    boolean set(int index) throws RemoteException { return call(SET_ACTIVE, EXTERNAL, index) != Integer.MIN_VALUE; }

    Config config(int index) throws RemoteException {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(descriptor);
            d.writeInt(index);
            d.writeInt(EXTERNAL);
            binder.transact(ATTRS, d, r, 0);
            if (r.readInt() != 0 || r.readInt() == 0) return null; // status, then non-null marker
            r.readInt();                                            // parcelable size
            int vsync = r.readInt(), w = r.readInt(), h = r.readInt();
            boolean sane = vsync >= 2_000_000 && vsync <= 50_000_000 && w >= 320 && w <= 8192 && h >= 200 && h <= 8192;
            return sane ? new Config(index, w, h, vsync) : null;
        } finally { d.recycle(); r.recycle(); }
    }

    /** Status-checked int reply; MIN_VALUE when the service reports an error (e.g. no display). */
    private int call(int code, int... args) throws RemoteException {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(descriptor);
            for (int a : args) d.writeInt(a);
            binder.transact(code, d, r, 0);
            if (r.readInt() != 0) return Integer.MIN_VALUE;
            return r.dataAvail() >= 4 ? r.readInt() : 0;
        } finally { d.recycle(); r.recycle(); }
    }
}
```

### Code sketch: the Start Mode thread

```java
/** A saved start mode: which monitor (by its fastest mode and name), and the mode to start in. */
record StartTarget(String monitorName, String fastestKey, int width, int height, float hz,
                   boolean rescue, boolean confirmed) {}

final class StartModeRacer implements Runnable {
    private final DisplayConfig dc;
    private final RefreshSession session;
    private volatile boolean armed;
    private int rememberedIndex = -1;

    public void run() {
        while (!Thread.interrupted()) {
            if (!armed || session.startTarget() == null) { LockSupport.parkNanos(200_000_000L); continue; }
            try {
                if (!dc.connected()) { LockSupport.parkNanos(1_000_000L); continue; }
                long seenAt = SystemClock.elapsedRealtimeNanos();
                StartTarget t = session.startTarget();
                Outcome o = trySet(t);
                session.onStartModeAttempt(t, o, seenAt); // logs timings; confirmation handled by the session tick
                armed = false;                            // one attempt per connection
            } catch (RemoteException e) {
                armed = false;
                session.disableStartMode("DisplayConfig failed: " + e);
            }
        }
    }

    private Outcome trySet(StartTarget t) throws RemoteException {
        int n = dc.count();
        if (n <= 0 || n > 512) return Outcome.NOT_LISTED;
        DisplayConfig.Config target = null, fastest = null;
        DisplayConfig.Config remembered = rememberedIndex >= 0 && rememberedIndex < n ? dc.config(rememberedIndex) : null;
        if (remembered != null && remembered.is(t.width(), t.height(), t.hz())) target = remembered;
        for (int i = 0; i < n; i++) {                // fastest is needed for the fingerprint anyway
            DisplayConfig.Config c = i == rememberedIndex && remembered != null ? remembered : dc.config(i);
            if (c == null) continue;
            if (fastest == null || c.vsyncNs() < fastest.vsyncNs()) fastest = c;
            if (target == null && c.is(t.width(), t.height(), t.hz())) target = c;
        }
        if (fastest == null || !key(fastest).equals(t.fastestKey())) return Outcome.OTHER_MONITOR;
        if (target == null) return Outcome.NOT_LISTED;
        rememberedIndex = target.index();
        if (dc.active() == target.index()) return Outcome.ALREADY;
        return dc.set(target.index()) ? Outcome.SET : Outcome.REFUSED;
    }
}
```

Whether the set **won** is decided after SurfaceFlinger adds the display. SF's active mode for
the external display must equal the target. HzUnlock's `DisplayControl.displays()` already
reads it.

- **Won:** treat it like a kept mode:
  `keptModes.put(id, modeKey(target))`, and `needsReconnect` returns false for it.
- **Lost, rescue:** `dc.set(divisorIndex)` once (for 288 that is 144), then arm again for the
  next hotplug. After two losses in a row, stop and show the standard-modes fallback.
- **Lost, speed-up:** leave the preferred mode alone. Offer "Replug to start at 144 Hz".

Ordering on the thread: an armed connection must skip HzUnlock's own connect handling. That
means no `setHighResolution` toggles and no reconnects until the outcome is known, which is at
most 3 s after SF adds the display.

### Confirmation and rollback (no black monitor stays black)

- **First use of a target.** A notification and the app both show "Monitor started at 144 Hz.
  Keep it? [Keep] [Undo]", with a 20 s timer. This reuses the root Monitor Mode flow: the same
  `MODE_CONFIRM_MS`, and `rollbackModeSwitch()` going to standard DeX modes.
- **Timer runs out, or Undo:**
  1. Clear the target.
  2. `DisplayControl.setHighResolution(false)`, which puts DeX in its standard modes and
     hotplugs.
  3. Leave high refresh on, with the slider at the phone's rates.

  The user's next physical replug reaches the monitor's own preferred mode, unchanged.
- **Display drops within `SHORT_LINK_MS` (20 s) of a start-mode connection:** count it as a
  failure for that target (`LinkDropTracker.LOST`). Two failures clear the target, like an Undo.
- **High Refresh off or service stopped:** never `setActiveConfig` back to config 0 (for
  DeX_Mod that is 288, the black mode). The normal off path already toggles high-res and
  reconnects, which restarts the monitor in its preferred mode. If HzUnlock dies mid-race, the
  monitor keeps the chosen mode until its next hotplug, and that mode was one the user confirmed
  or the safe half rate.
- **Confirmed targets** start without asking from then on. They are cleared by "Forget start
  mode", or when the fingerprint stops matching.

### Rescue: deciding automatically

A rescue target is created automatically only when:

- `LinkDropTracker` reports a monitor `LOST` or `REPEATED` at its preferred rate, at least two
  drops within `SHORT_LINK_MS`; and
- that rate is above 200 Hz, or above about 90% of the estimated link (`pixel clock × 24 >
  0.9 × 17.28 Gbps`); and
- the list contains an exact half of that rate at the same resolution. Use a third if there's no
  half.

The first such start still asks for confirmation. A lost black monitor recovers either way.

### UI

- Add a **"Monitor start mode"** row to the monitor card, with a picker of the monitor's real
  modes: the current resolution first, then the fastest first. Use the existing `modeChoices()`.
  - The row reads "Default (288 Hz)" or "144 Hz at 1920 × 1080 (kept)".
  - Without DisplayConfig (Exynos) it reads "Not available on this phone".
- Keep the slider, renamed **"App frame rate"**. Label lower stops "paced by the phone screen".
- Messages:
  - Preferred too slow: "Your monitor asks for 60 Hz when it connects, but it lists 144 Hz.
    HzUnlock can start it at 144 Hz instead. [Start at 144 Hz]"
    - Pressing it sets a speed-up target, then asks: "Replug the monitor, or tap Reconnect."
      A reconnect only works when DeX actually hotplugs.
  - Rescue: "The monitor lost its picture at 288 Hz. HzUnlock will start it at 144 Hz.
    [Try now] [Use standard DeX modes]"
  - DeX_Mod 4K: "In this mode the monitor starts at 60 Hz, and reconnecting won't change it.
    Choose a start mode, or switch the monitor to its 1080p mode."
  - Remove "Lower DeX's screen resolution to use it". Replace it with a start-mode suggestion for
    that resolution.

### Copy Report additions

- The external display's timing line from `dumpsys SurfaceFlinger` (`h_total`, `v_total`,
  `clk`). This gives link use directly.
- The DisplayConfig config list. It is what `listmodes.sh` prints, including every duplicate.
- Log lines kept first, before the general filter:
  - `SDM` create, notify and destroy hotplug lines
  - SF `initial mode`
  - `secdp` / `DP` lines, if readable
  - Start Mode attempts, with times relative to HWC create
- Brightness spam dropped (`setDisplayBrightness`, `surface lcd`).
- `/sys/class/dp_sec/monitor_info`, which is readable as shell.

These show whether a black monitor is a **disconnect loop** (repeated create and destroy) or a
**silent black** (one connection, no picture).

## Alternatives considered

| Option | Verdict |
|---|---|
| SF `setDesiredDisplayModeSpecs` / render-rate on the external display | Blocked by Samsung's external guard |
| DeX resolution picker | Changes logical size only |
| `vendor.display.hdmi_cfg_idx` | Root only. Start Mode replaces it. |
| `dp_sec` sysfs (`dex`, `bw_code`, `dp_error_info`) | System-owned; shell is denied |
| Shell `service call` loop | ~34 ms per call, too slow to win. Fine as a live-switch diagnostic. |
| Live switch only, without the race | SF keeps the old vsync model. Acceptable only for an exact divisor, as a rescue. |
| SF `LinkUnstable` re-read path | Qualcomm's composer never sends it |
| Cable or alt-mode | 4 lanes are evident, and the cap is in software. A powered monitor still helps portables. |
| `cmd game` per-app frame-rate override | Could halve app work in the live rescue. Untested. |
| **Soft replug via `IUsbManager.resetUsbPort` / `setPortRoles` (shell has `MANAGE_USB`)** | **Idea, untested.** It would give a real hotplug where the DeX toggle doesn't, and would remove the "please replug" step. It may also interrupt charging and USB adb. Test on the S24 before use. |

## Untested

- Whether the S25 (SM8750) has the same HBR2 cap and the same create → hotplug gap. The gap
  depends on `WaitForResources`; it was 6–17 ms on the S24.
- Whether the ZX27BU shows a picture at 144, 200 or 240 over the S25 link, and whether the
  288 black is a disconnect loop or a silent black.
- Whether Samsung's poor-connection counter, which persists through the 2-minute backoff, blocks
  a fresh start right after a loop.
- Whether a cross-resolution start mode works: Caroloux's TV, 4K60 → 1080p120. DeX should
  simply see a 1080p display.
- Whether DeX accepts 2160p @ 100 at 6-bit, and how it looks.
- What the UPERFECT Switch 1 / Switch 2 settings change in the EDID.
- `resetUsbPort` as a soft replug.

## Test plan for users

Both scripts run as shell: `adb shell sh /sdcard/<script>` from a PC, or Shizuku's `rish` on the
phone. They are in
`C:\Users\npepo\AppData\Local\Temp\claude\392582c82b491c04\5fb235e1-acfa-4cf4-a3a4-11aa21adfdf9\scratchpad\probe\`.

`listmodes.sh` is read-only and was verified on the S24 (`configs=28 active=0`,
`0: 1920x1080 @ 240 Hz`, …):

```sh
S=vendor.qti.hardware.display.config.IDisplayConfig/default
n=$((0x$(service call $S 4 i32 2 | sed -n 's/.*00000000 \([0-9a-f]\{8\}\).*/\1/p')))
a=$((0x$(service call $S 5 i32 2 | sed -n 's/.*00000000 \([0-9a-f]\{8\}\).*/\1/p')))
echo "configs=$n active=$a"
i=0; while [ $i -lt $n ]; do
  service call $S 7 i32 $i i32 2 | tr -d '\n' | sed -n 's/.*0x00000000: 00000000 00000001 [0-9a-f]* \([0-9a-f]*\).*0x00000010: \([0-9a-f]*\) \([0-9a-f]*\).*/\1 \2 \3/p' | { read v x y; echo "$i: $((0x$x))x$((0x$y)) @ $(( (2000000000 / 0x$v + 1) / 2 )) Hz"; }
  i=$((i+1)); done
```

`startmode.sh <width> <height> <hz>` waits for the next connection and switches it to that mode,
matching by mode rather than index. From shell this is a live switch tens of ms after connect.
It tests **whether the monitor shows a picture in that mode on this link**, not the final
behaviour. Undo it by replugging. The full script is in the scratchpad file; its core is:

```sh
while [ $(date +%s) -lt $end ]; do
  connected || continue
  [ -n "$idx" ] && [ "$(mode $idx)" = "$W $H $HZ" ] || idx=$(find_idx)
  [ -z "$idx" ] && { echo "not listed this time"; exit 1; }
  service call $S 6 i32 2 i32 $idx >/dev/null; echo "switched to $idx"; exit 0
done
```

### For DeX_Mod (S25, ZX27BU)

1. Monitor in 1080p UserMode, HzUnlock High Refresh on. Let it go black twice. Then send:
   - `sh listmodes.sh`
   - `dumpsys SurfaceFlinger | grep -iE "h_total|clk"` (run during a brief picture if possible)
   - `logcat -d | grep -E "SDM|initial mode|secdp|HWTVDRM"`
2. While it loops, run `sh startmode.sh 1920 1080 144`. Does the picture stay?
   - If yes, try `200` and then `240` the same way.
3. Turn High Refresh off. Does 1080p mode then stay up, at 60?
4. Copy Report with the monitor's Switch setting at Standard, Switch 1 and Switch 2, in both
   UserModes.
5. In 4K UserMode: `sh listmodes.sh`. Is 2560x1440 @ 120/144 listed?

### For Lethyzs, audiophile-disorder, Caroloux (preferred mode 60)

1. `sh listmodes.sh`.
2. `sh startmode.sh 1920 1080 144` (or 120), then replug the monitor.
   - Does the monitor's OSD show 144 with a picture? That proves the link carries it.
   - HzUnlock will still show 60 until Start Mode exists, because SF reads 60 first from shell.
3. Caroloux (TV): `sh startmode.sh 1920 1080 120`, then replug.

### Then

A HzUnlock test build with Start Mode. Ask each user for a Copy Report after one connect and
after a replug.
