// Run as root via app_process:
//   adb shell su -c 'CLASSPATH=/data/local/tmp/hz120.dex app_process / Hz120 show'
//   adb shell su -c 'CLASSPATH=/data/local/tmp/hz120.dex app_process / Hz120 loop'
// Overrides SurfaceFlinger's refresh-rate policy for external displays so that
// DeX's LowRefreshRateToken (a 0-60Hz vote) no longer caps them. "loop" keeps
// re-applying because DisplayManager pushes its own policy whenever votes change.
import android.os.IBinder;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class Hz120 {
    static Class<?> sc;

    static Object get(Object o, String f) throws Exception {
        Field fl = o.getClass().getField(f);
        return fl.get(o);
    }

    static Object range(float lo, float hi) throws Exception {
        Class<?> r = Class.forName("android.view.SurfaceControl$RefreshRateRange");
        return r.getConstructor(float.class, float.class).newInstance(lo, hi);
    }

    static Object ranges(Object physical, Object render) throws Exception {
        Class<?> rr = Class.forName("android.view.SurfaceControl$RefreshRateRanges");
        Class<?> r = Class.forName("android.view.SurfaceControl$RefreshRateRange");
        return rr.getConstructor(r, r).newInstance(physical, render);
    }

    static String describe(Object specs) throws Exception {
        if (specs == null) return "null";
        Object p = get(specs, "primaryRanges"), a = get(specs, "appRequestRanges");
        return "defaultMode=" + get(specs, "defaultMode") + " primary{phys=" + rangeStr(get(p, "physical"))
                + " render=" + rangeStr(get(p, "render")) + "} app{phys=" + rangeStr(get(a, "physical"))
                + " render=" + rangeStr(get(a, "render")) + "}";
    }

    static String rangeStr(Object r) throws Exception {
        return "[" + get(r, "min") + "," + get(r, "max") + "]";
    }

    /** Returns {modeId, refreshRate} of the fastest mode matching the active resolution. */
    static float[] fastestMode(long id) throws Exception {
        Object dyn = sc.getMethod("getDynamicDisplayInfo", long.class).invoke(null, id);
        Object[] modes = (Object[]) get(dyn, "supportedDisplayModes");
        int active = (Integer) get(dyn, "activeDisplayModeId");
        int w = 0, h = 0;
        for (Object m : modes)
            if ((Integer) get(m, "id") == active) {
                w = (Integer) get(m, "width");
                h = (Integer) get(m, "height");
            }
        float best = 0;
        int bestId = active;
        for (Object m : modes) {
            float hz = (Float) get(m, "peakRefreshRate");
            if ((Integer) get(m, "width") == w && (Integer) get(m, "height") == h && hz > best) {
                best = hz;
                bestId = (Integer) get(m, "id");
            }
        }
        return new float[] {bestId, best, active};
    }

    static boolean apply(long id, IBinder tok, boolean quiet) throws Exception {
        Object cur = sc.getMethod("getDesiredDisplayModeSpecs", IBinder.class).invoke(null, tok);
        float[] fm = fastestMode(id);
        float hz = fm[1];
        Object p = get(cur, "primaryRanges");
        float curMax = (Float) get(get(p, "render"), "max");
        float curMin = (Float) get(get(p, "physical"), "min");
        float curRenderMin = (Float) get(get(p, "render"), "min");
        if (curMax >= hz - 0.5f && curMin >= hz - 1f && curRenderMin >= hz - 1f
                && (Integer) get(cur, "defaultMode") == (int) fm[0]) return false;

        // The spec's fields are final, so build a new one with the full constructor,
        // carrying over the token, group switching, idle config and work duration.
        Class<?> ds = Class.forName("android.view.SurfaceControl$DesiredDisplayModeSpecs");
        Class<?> rrc = Class.forName("android.view.SurfaceControl$RefreshRateRanges");
        Class<?> idle = Class.forName("android.view.SurfaceControl$IdleScreenRefreshRateConfig");
        Class<?> work = Class.forName("android.view.SurfaceControl$WorkDuration");
        Object token = null, idleCfg = null, workDur = null;
        boolean groupSwitch = false;
        for (Field f : ds.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getType() == IBinder.class) token = f.get(cur);
            else if (f.getType() == boolean.class) groupSwitch = f.getBoolean(cur);
            else if (f.getType() == idle) idleCfg = f.get(cur);
            else if (f.getType() == work) workDur = f.get(cur);
        }
        Object spec = ds.getConstructor(IBinder.class, int.class, boolean.class, rrc, rrc, idle, work)
                .newInstance(token, (int) fm[0], groupSwitch,
                        // Pin both the physical and render rate so idle content can't drop to 60.
                        ranges(range(hz - 0.5f, hz + 0.5f), range(hz - 0.5f, hz + 0.5f)),
                        ranges(range(hz - 0.5f, hz + 0.5f), range(hz - 0.5f, hz + 0.5f)), idleCfg, workDur);
        Object arr = Array.newInstance(ds, 1);
        Array.set(arr, 0, spec);
        boolean ok = (Boolean) sc.getMethod("setDesiredDisplayModeSpecs", IBinder.class, arr.getClass())
                .invoke(null, tok, arr);
        System.out.println("display " + id + ": was " + describe(cur) + "  -> set max " + hz + "Hz (mode "
                + (int) fm[0] + ", active mode " + (int) fm[2] + ") ok=" + ok);
        return true;
    }

    public static void main(String[] args) throws Exception {
        sc = Class.forName("android.view.SurfaceControl");
        String mode = args.length > 0 ? args[0] : "show";
        boolean includeInternal = args.length > 1 && args[1].equals("all");
        do {
            long[] ids = (long[]) sc.getMethod("getPhysicalDisplayIds").invoke(null);
            for (long id : ids) {
                IBinder tok = (IBinder) sc.getMethod("getPhysicalDisplayToken", long.class).invoke(null, id);
                if (tok == null) continue;
                Object st = sc.getMethod("getStaticDisplayInfo", long.class).invoke(null, id);
                boolean internal = (Boolean) get(st, "isInternal");
                if (mode.equals("show")) {
                    Object cur = sc.getMethod("getDesiredDisplayModeSpecs", IBinder.class).invoke(null, tok);
                    float[] fm = fastestMode(id);
                    System.out.println("display " + id + (internal ? " internal" : " EXTERNAL") + " activeMode="
                            + (int) fm[2] + " fastest=" + fm[1] + "Hz(mode " + (int) fm[0] + ") " + describe(cur));
                } else if (!internal || includeInternal) {
                    try {
                        apply(id, tok, false);
                    } catch (Exception e) {
                        // A display unplugged mid-update must not end the loop.
                        System.out.println("display " + id + ": apply failed: " + e);
                    }
                }
            }
            if (mode.equals("loop")) Thread.sleep(50);
        } while (mode.equals("loop"));
    }
}
