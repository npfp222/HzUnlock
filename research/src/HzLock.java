// Root-only refresh-rate lock for Samsung DeX (runs via app_process as uid 0).
//   CLASSPATH=/data/adb/dexhz/hzlock.dex app_process / HzLock [run|show]
//
// - Pins each external display's SurfaceFlinger policy (physical + render) to its
//   active mode, overriding DeX's LowRefreshRateToken (0-60Hz). The phone's own
//   panel is left alone so its adaptive refresh keeps working.
// - Keeps DexController's high-resolution switch on, so the kernel's DisplayPort
//   driver offers the monitor's full mode list.
// - Learns the best start mode: SurfaceFlinger never switches an external display's
//   mode after connect, so if a monitor comes up slower than its fastest mode, the
//   Qualcomm start-mode property (vendor.display.hdmi_cfg_idx) is updated and the
//   user is asked to replug once.
import android.os.IBinder;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class HzLock {
    static final String DIR = "/data/adb/dexhz";
    static final String START_MODE_FILE = DIR + "/startmode";
    static final String LOG = DIR + "/hzlock.log";
    static final String PROP = "vendor.display.hdmi_cfg_idx";
    static final long TICK_MS = 40;

    static Class<?> sc, ds, rrc, rc, idle, work;
    static final Map<Long, Integer> announced = new HashMap<>();
    static long reapplies = 0;
    static boolean firstPass = true;

    public static void main(String[] args) throws Exception {
        sc = Class.forName("android.view.SurfaceControl");
        ds = Class.forName("android.view.SurfaceControl$DesiredDisplayModeSpecs");
        rrc = Class.forName("android.view.SurfaceControl$RefreshRateRanges");
        rc = Class.forName("android.view.SurfaceControl$RefreshRateRange");
        idle = Class.forName("android.view.SurfaceControl$IdleScreenRefreshRateConfig");
        work = Class.forName("android.view.SurfaceControl$WorkDuration");
        String mode = args.length > 0 ? args[0] : "run";
        if (mode.equals("show")) {
            for (long id : ids()) System.out.println(describe(id));
            return;
        }
        new File(DIR).mkdirs();
        // Several start paths (KernelSU service.d, SamTimer's post-root hook, the PC
        // script) can fire together; only the first instance keeps running.
        java.nio.channels.FileChannel lockChannel = new java.io.RandomAccessFile(DIR + "/hzlock.lock", "rw").getChannel();
        if (lockChannel.tryLock() == null) {
            System.out.println("HzLock already running; exiting");
            return;
        }
        log("HzLock start; startmode=" + readStartMode());
        applyStartModeProp();
        long lastHiRes = 0;
        while (true) {
            long now = System.currentTimeMillis();
            if (now - lastHiRes > 5000) {
                ensureHiRes();
                lastHiRes = now;
            }
            long[] present = ids();
            for (long id : present) {
                try {
                    lock(id);
                } catch (Exception e) {
                    log("display " + id + ": lock failed: " + e);
                }
            }
            firstPass = false;
            java.util.Set<Long> live = new java.util.HashSet<>();
            for (long id : present) live.add(id);
            announced.keySet().retainAll(live);
            updatePacesetter(present);
            Thread.sleep(TICK_MS);
        }
    }

    // ---- display policy -------------------------------------------------------

    static long[] ids() throws Exception {
        return (long[]) sc.getMethod("getPhysicalDisplayIds").invoke(null);
    }

    static IBinder token(long id) throws Exception {
        return (IBinder) sc.getMethod("getPhysicalDisplayToken", long.class).invoke(null, id);
    }

    static Object get(Object o, String f) throws Exception {
        return o.getClass().getField(f).get(o);
    }

    /** {fastestModeId, fastestHz, activeModeId, activeHz, width, height} at the active resolution. */
    static float[] modes(long id) throws Exception {
        Object dyn = sc.getMethod("getDynamicDisplayInfo", long.class).invoke(null, id);
        Object[] all = (Object[]) get(dyn, "supportedDisplayModes");
        int active = (Integer) get(dyn, "activeDisplayModeId");
        int w = 0, h = 0;
        float activeHz = 0;
        for (Object m : all)
            if ((Integer) get(m, "id") == active) {
                w = (Integer) get(m, "width");
                h = (Integer) get(m, "height");
                activeHz = (Float) get(m, "peakRefreshRate");
            }
        float best = 0;
        int bestId = active;
        for (Object m : all) {
            float hz = (Float) get(m, "peakRefreshRate");
            if ((Integer) get(m, "width") == w && (Integer) get(m, "height") == h && hz > best) {
                best = hz;
                bestId = (Integer) get(m, "id");
            }
        }
        return new float[] {bestId, best, active, activeHz, w, h};
    }

    static boolean isInternal(long id) throws Exception {
        Object st = sc.getMethod("getStaticDisplayInfo", long.class).invoke(null, id);
        return (Boolean) get(st, "isInternal");
    }

    static Object range(float lo, float hi) throws Exception {
        return rc.getConstructor(float.class, float.class).newInstance(lo, hi);
    }

    static final java.util.Set<Long> released = new java.util.HashSet<>();

    // Older HzLock builds pinned the phone panel at its fastest rate, and that pin
    // outlives the process until DisplayManager next pushes its own policy. Widen it
    // once to [0, fastest] so the panel's adaptive refresh can work again.
    static void releaseInternal(long id) throws Exception {
        if (!released.add(id)) return;
        IBinder tok = token(id);
        if (tok == null) return;
        float[] m = modes(id);
        Object cur = sc.getMethod("getDesiredDisplayModeSpecs", IBinder.class).invoke(null, tok);
        Object p = get(cur, "primaryRanges");
        float physMin = (Float) get(get(p, "physical"), "min");
        if (physMin < m[1] - 1f) return; // not pinned; leave Android's policy alone
        Object token = null, idleCfg = null, workDur = null;
        boolean groupSwitch = false;
        for (Field f : ds.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getType() == IBinder.class) token = f.get(cur);
            else if (f.getType() == boolean.class) groupSwitch = f.getBoolean(cur);
            else if (f.getType() == idle) idleCfg = f.get(cur);
            else if (f.getType() == work) workDur = f.get(cur);
        }
        Object open = rrc.getConstructor(rc, rc).newInstance(range(0f, m[1]), range(0f, m[1]));
        Object spec = ds.getConstructor(IBinder.class, int.class, boolean.class, rrc, rrc, idle, work)
                .newInstance(token, (int) m[0], groupSwitch, open, open, idleCfg, workDur);
        Object arr = Array.newInstance(ds, 1);
        Array.set(arr, 0, spec);
        sc.getMethod("setDesiredDisplayModeSpecs", IBinder.class, arr.getClass()).invoke(null, tok, arr);
        log("display " + id + " (phone) released from " + Math.round(physMin) + "Hz pin to 0-" + Math.round(m[1]) + "Hz");
    }

    static void lock(long id) throws Exception {
        // The phone's own panel is left to Android's adaptive refresh (down to 1Hz when
        // nothing moves); pinning it at 120 costs battery and DeX doesn't need it.
        if (isInternal(id)) {
            releaseInternal(id);
            return;
        }
        IBinder tok = token(id);
        if (tok == null) return;
        float[] m = modes(id);
        // An external display keeps its connect-time mode, so lock to what is actually
        // active there.
        boolean internal = false;
        int targetMode = (int) m[2];
        float hz = m[3];
        checkStartMode(id, m);

        Object cur = sc.getMethod("getDesiredDisplayModeSpecs", IBinder.class).invoke(null, tok);
        Object p = get(cur, "primaryRanges");
        float physMin = (Float) get(get(p, "physical"), "min");
        float renderMin = (Float) get(get(p, "render"), "min");
        float renderMax = (Float) get(get(p, "render"), "max");
        if ((Integer) get(cur, "defaultMode") == targetMode && physMin >= hz - 1f && renderMin >= hz - 1f
                && renderMax >= hz - 0.5f) return;

        Object token = null, idleCfg = null, workDur = null;
        boolean groupSwitch = false;
        for (Field f : ds.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getType() == IBinder.class) token = f.get(cur);
            else if (f.getType() == boolean.class) groupSwitch = f.getBoolean(cur);
            else if (f.getType() == idle) idleCfg = f.get(cur);
            else if (f.getType() == work) workDur = f.get(cur);
        }
        Object pinned = rrc.getConstructor(rc, rc).newInstance(range(hz - 0.5f, hz + 0.5f), range(hz - 0.5f, hz + 0.5f));
        Object spec = ds.getConstructor(IBinder.class, int.class, boolean.class, rrc, rrc, idle, work)
                .newInstance(token, targetMode, groupSwitch, pinned, pinned, idleCfg, workDur);
        Object arr = Array.newInstance(ds, 1);
        Array.set(arr, 0, spec);
        sc.getMethod("setDesiredDisplayModeSpecs", IBinder.class, arr.getClass()).invoke(null, tok, arr);
        reapplies++;
        if (reapplies <= 5 || reapplies % 50 == 0)
            log("display " + id + (internal ? " (phone)" : " (monitor)") + " re-locked to " + Math.round(hz)
                    + "Hz; was render " + Math.round(renderMin) + "-" + Math.round(renderMax) + " [#" + reapplies + "]");
    }

    static String describe(long id) throws Exception {
        float[] m = modes(id);
        Object cur = sc.getMethod("getDesiredDisplayModeSpecs", IBinder.class).invoke(null, token(id));
        Object p = get(cur, "primaryRanges");
        return "display " + id + (isInternal(id) ? " phone" : " MONITOR") + " " + (int) m[4] + "x" + (int) m[5]
                + " active=" + Math.round(m[3]) + "Hz fastest=" + Math.round(m[1]) + "Hz policy phys="
                + rangeStr(get(p, "physical")) + " render=" + rangeStr(get(p, "render"));
    }

    static String rangeStr(Object r) throws Exception {
        return "[" + Math.round((Float) get(r, "min")) + "," + Math.round((Float) get(r, "max")) + "]";
    }

    // ---- start mode learning --------------------------------------------------

    static void checkStartMode(long id, float[] m) throws Exception {
        if (announced.containsKey(id)) return;
        announced.put(id, Math.round(m[3]));
        String best = (int) m[4] + ":" + (int) m[5] + ":" + Math.round(m[1]);
        if (firstPass && m[3] <= 61f) {
            // The monitor was connected before HzLock (and the hi-res switch / start
            // mode) existed this boot, so its mode list and start mode are stale.
            log("monitor was already connected at startup (" + Math.round(m[3]) + "Hz); reconnecting it");
            virtualReplug("it was connected before high refresh was ready");
        } else if (m[1] > m[3] + 1f) {
            writeStartMode(best);
            applyStartModeProp();
            log("monitor came up at " + Math.round(m[3]) + "Hz but offers " + Math.round(m[1]) + "Hz; start mode now " + best);
            virtualReplug("it came up at " + Math.round(m[3]) + "Hz but supports " + Math.round(m[1]) + "Hz");
        } else {
            replugAttempts = 0;
            log("monitor connected at " + Math.round(m[3]) + "Hz (fastest available)");
            notify("Monitor locked at " + Math.round(m[3]) + "Hz", (int) m[4] + "x" + (int) m[5] + " @ "
                    + Math.round(m[3]) + "Hz, DeX 60Hz cap removed.");
        }
    }

    // Samsung's DisplayPort driver reconnects the monitor whenever the DeX "run" state in
    // /sys/class/dp_sec/dex changes (it does this to apply DeX resolutions). Toggling it to
    // 17 (DeX on an external display) and back to 0 (no DeX restriction) re-runs the whole
    // connect, so the Qualcomm start-mode property and full mode list take effect without
    // a physical replug.
    static final String DP_DEX = "/sys/class/dp_sec/dex";
    static int replugAttempts = 0;

    static void virtualReplug(String why) {
        if (replugAttempts >= 2) {
            log("virtual replug already tried twice; asking the user instead");
            notify("Replug the monitor once", "High refresh is ready, but the monitor needs a manual replug (" + why + ").");
            return;
        }
        replugAttempts++;
        new Thread(() -> {
            try {
                // DexController applies the hi-res switch to the same sysfs node
                // asynchronously; if our writes race with it the driver sees no state
                // change and skips the reconnect.
                Thread.sleep(4000);
                writeSysfs(DP_DEX, "17");
                Thread.sleep(6000);
                writeSysfs(DP_DEX, "0");
                log("virtual replug done (" + why + ")");
            } catch (Exception e) {
                log("virtual replug failed: " + e);
                notify("Replug the monitor once", "Unplug the monitor and plug it back in to get the fastest mode.");
            }
        }, "virtual-replug").start();
    }

    static void writeSysfs(String path, String value) throws Exception {
        try (FileWriter w = new FileWriter(path)) {
            w.write(value);
        }
    }

    static String readStartMode() {
        try {
            return new String(Files.readAllBytes(new File(START_MODE_FILE).toPath())).trim();
        } catch (Exception e) {
            return "1920:1080:120";
        }
    }

    static void writeStartMode(String v) {
        try (FileWriter w = new FileWriter(START_MODE_FILE)) {
            w.write(v + "\n");
        } catch (Exception e) {
            log("cannot save start mode: " + e);
        }
    }

    static void applyStartModeProp() {
        String v = readStartMode();
        String ksud = new File("/data/adb/ksud").exists() ? "/data/adb/ksud" : DIR + "/ksud";
        exec(ksud, "resetprop", PROP, v);
    }

    // ---- pacesetter -----------------------------------------------------------------

    // SurfaceFlinger times every app's frames from one "pacesetter" display, which is
    // always the phone panel (max 120Hz). Samsung's build exposes forcePacesetter /
    // resetForcedPacesetter on ISurfaceComposer (AIDL transactions 0x54 / 0x55), so a
    // faster monitor can drive app vsync instead. The phone panel keeps its own mode.
    static final int TX_FORCE_PACESETTER = 0x54;
    static final int TX_RESET_PACESETTER = 0x55;
    static Long forcedPacesetter = null;

    static void updatePacesetter(long[] present) {
        try {
            long want = -1;
            float internalHz = 0, bestExternalHz = 0;
            for (long id : present) {
                float hz = modes(id)[3];
                if (isInternal(id)) internalHz = Math.max(internalHz, hz);
                else if (hz > bestExternalHz) {
                    bestExternalHz = hz;
                    want = id;
                }
            }
            boolean useExternal = want != -1 && bestExternalHz > internalHz + 1f;
            if (useExternal && (forcedPacesetter == null || forcedPacesetter != want)) {
                surfaceFlingerCall(TX_FORCE_PACESETTER, want);
                forcedPacesetter = want;
                log("pacesetter -> monitor " + want + " (" + Math.round(bestExternalHz) + "Hz)");
            } else if (!useExternal && forcedPacesetter != null) {
                surfaceFlingerCall(TX_RESET_PACESETTER, null);
                forcedPacesetter = null;
                log("pacesetter -> phone (reset)");
            }
        } catch (Exception e) {
            log("pacesetter update failed: " + e);
        }
    }

    static void surfaceFlingerCall(int code, Long displayId) throws Exception {
        IBinder sf = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "SurfaceFlingerAIDL");
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(sf.getInterfaceDescriptor());
            if (displayId != null) data.writeLong(displayId);
            sf.transact(code, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ---- DeX high-resolution switch ---------------------------------------------

    static void ensureHiRes() {
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            Object mt = atm.getClass().getMethod("getMultiTaskingBinder").invoke(atm);
            mt.getClass().getMethod("enableHighResolutionsForExternalDesktop", boolean.class).invoke(mt, true);
        } catch (Exception e) {
            log("hi-res switch failed: " + e);
        }
    }

    // ---- helpers ----------------------------------------------------------------

    static void notify(String title, String text) {
        exec("/system/bin/cmd", "notification", "post", "-S", "bigtext", "-t", title, "dexhz", text);
    }

    static void exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
        } catch (Exception e) {
            log("exec " + cmd[0] + " failed: " + e);
        }
    }

    static synchronized void log(String s) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date()) + " " + s;
        System.out.println(line);
        try {
            File f = new File(LOG);
            if (f.length() > 256 * 1024) f.renameTo(new File(LOG + ".old"));
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(line + "\n");
            }
        } catch (Exception ignored) {
        }
    }
}
