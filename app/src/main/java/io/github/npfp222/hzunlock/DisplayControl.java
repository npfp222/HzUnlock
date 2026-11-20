package io.github.npfp222.hzunlock;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.Parcel;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Hidden SurfaceControl, SurfaceFlinger, and Samsung DeX calls, looked up at runtime so that a
 * missing or differently shaped API turns a feature off instead of crashing. Works from the ADB
 * shell UID (2000), which holds ACCESS_SURFACE_FLINGER, and from root.
 */
final class DisplayControl {
    private static final Class<?> SC = load("android.view.SurfaceControl");
    private static final Method IDS = find(SC, "getPhysicalDisplayIds");
    private static final Method TOKEN = find(SC, "getPhysicalDisplayToken", long.class);
    private static final Method STATIC_INFO = find(SC, "getStaticDisplayInfo", long.class);
    private static final Method DYNAMIC_INFO = find(SC, "getDynamicDisplayInfo", long.class);
    private static final Method GET_SPECS = find(SC, "getDesiredDisplayModeSpecs", IBinder.class);
    private static final Method SET_SPECS = findSetSpecs();
    private static final SurfaceFlingerCodes.Codes CODES = SurfaceFlingerCodes.discover();

    private static DisplayManager displayManager;

    private DisplayControl() {}

    static void init(Context context) {
        displayManager = context.getSystemService(DisplayManager.class);
    }

    record Mode(int id, int width, int height, float hz) {}

    /** One physical display. {@code on} reflects DisplayManager's power state when known. */
    record Info(long id, boolean internal, boolean on, String name, Mode active, Mode fastest, List<Mode> modes) {
        boolean belowFastest() {
            return fastest.hz() > active.hz() + 1f;
        }
    }

    // ---- capabilities ----------------------------------------------------------------------

    /** SurfaceFlinger display info with real mode ids (needed to hold a policy). */
    static boolean canReadModes() {
        return IDS != null && STATIC_INFO != null && DYNAMIC_INFO != null;
    }

    /** Holding a display's refresh policy (used to pace apps from the phone panel). */
    static boolean canHoldPolicy() {
        return canReadModes() && TOKEN != null && GET_SPECS != null && SET_SPECS != null;
    }

    /** SurfaceFlinger's forcePacesetter calls exist and their numbers were found. */
    static boolean hasPacesetterCalls() {
        return CODES != null;
    }

    static String pacesetterCodes() {
        return CODES == null ? "none" : String.format("0x%x/0x%x", CODES.force(), CODES.reset());
    }

    static boolean hasHighResolutionSwitch() {
        try {
            dexService().getClass().getMethod("enableHighResolutionsForExternalDesktop", boolean.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- displays --------------------------------------------------------------------------

    static List<Info> displays() throws Exception {
        Map<Long, Boolean> power = powerStates();
        List<Info> result = new ArrayList<>();
        if (canReadModes()) {
            for (long id : (long[]) IDS.invoke(null)) {
                Boolean on = power.get(id);
                result.add(sfInfo(id, on == null || on));
            }
            return result;
        }
        // Fallback: DisplayManager's public view. Mode ids are DisplayManager's, so no policy holds.
        if (displayManager == null) return result;
        for (Display display : displayManager.getDisplays()) {
            Long physicalId = physicalId(display);
            if (physicalId == null) continue;
            Display.Mode active = display.getMode();
            List<Mode> modes = new ArrayList<>();
            Mode activeMode = null;
            for (Display.Mode m : display.getSupportedModes()) {
                Mode mode = new Mode(-1, m.getPhysicalWidth(), m.getPhysicalHeight(), m.getRefreshRate());
                modes.add(mode);
                if (m.getModeId() == active.getModeId()) activeMode = mode;
            }
            if (activeMode == null) continue;
            boolean internal = display.getDisplayId() == Display.DEFAULT_DISPLAY;
            result.add(new Info(physicalId, internal, display.getState() == Display.STATE_ON,
                    internal ? "Phone screen" : display.getName(), activeMode, fastest(activeMode, modes), modes));
        }
        return result;
    }

    private static Info sfInfo(long id, boolean on) throws ReflectiveOperationException {
        Object stat = STATIC_INFO.invoke(null, id);
        Object dyn = DYNAMIC_INFO.invoke(null, id);
        boolean internal = (Boolean) field(stat, "isInternal");
        String name = internal ? "Phone screen" : "External display";
        Object product = optional(stat, "deviceProductInfo");
        if (product != null) {
            Object productName = product.getClass().getMethod("getName").invoke(product);
            if (productName instanceof String text && !text.isBlank()) name = text;
        }
        int activeId = (Integer) field(dyn, "activeDisplayModeId");
        List<Mode> modes = new ArrayList<>();
        Mode active = null;
        for (Object raw : (Object[]) field(dyn, "supportedDisplayModes")) {
            Object hz = optional(raw, "peakRefreshRate");
            if (hz == null) hz = field(raw, "refreshRate");
            Mode mode = new Mode((Integer) field(raw, "id"), (Integer) field(raw, "width"),
                    (Integer) field(raw, "height"), (Float) hz);
            modes.add(mode);
            if (mode.id() == activeId) active = mode;
        }
        if (active == null) throw new IllegalStateException("SurfaceFlinger reported no active mode.");
        return new Info(id, internal, on, name, active, fastest(active, modes), modes);
    }

    private static Mode fastest(Mode active, List<Mode> modes) {
        Mode fastest = active;
        for (Mode mode : modes) {
            if (mode.width() == active.width() && mode.height() == active.height() && mode.hz() > fastest.hz()) {
                fastest = mode;
            }
        }
        return fastest;
    }

    /** Physical display id → screen on, from DisplayManager (empty when unavailable). */
    private static Map<Long, Boolean> powerStates() {
        Map<Long, Boolean> states = new HashMap<>();
        if (displayManager == null) return states;
        try {
            for (Display display : displayManager.getDisplays()) {
                Long physicalId = physicalId(display);
                if (physicalId != null) states.put(physicalId, display.getState() == Display.STATE_ON);
            }
        } catch (RuntimeException ignored) {
            // Power state is a hint for picking the active phone panel on foldables.
        }
        return states;
    }

    /** Display.getAddress() is a system API; reach it by reflection. Null for non-physical displays. */
    private static Long physicalId(Display display) {
        try {
            Object address = Display.class.getMethod("getAddress").invoke(display);
            if (address == null || !address.getClass().getName().endsWith("$Physical")) return null;
            return (Long) address.getClass().getMethod("getPhysicalDisplayId").invoke(address);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static IBinder token(long id) throws ReflectiveOperationException {
        return TOKEN == null ? null : (IBinder) TOKEN.invoke(null, id);
    }

    // ---- desired display mode specs ------------------------------------------------------

    /** A fresh copy of the display's policy. SurfaceControl returns a new object on every read. */
    static Object readSpecs(IBinder token) throws ReflectiveOperationException {
        return GET_SPECS.invoke(null, token);
    }

    static void writeSpecs(IBinder token, Object specs) throws ReflectiveOperationException, IOException {
        Object argument = specs;
        if (SET_SPECS.getParameterTypes()[1].isArray()) {
            argument = Array.newInstance(specs.getClass(), 1);
            Array.set(argument, 0, specs);
        }
        Object result = SET_SPECS.invoke(null, token, argument);
        if (Boolean.FALSE.equals(result)) throw new IOException("SurfaceFlinger rejected the refresh policy.");
    }

    /**
     * Reads the display's policy and pins its default mode and physical/render ranges, by editing
     * fields on the fresh copy. That avoids constructors, whose parameters change between releases.
     */
    static Object pinnedSpecs(IBinder token, int modeId, float physicalHz, float renderHz)
            throws ReflectiveOperationException {
        Object specs = readSpecs(token);
        specs.getClass().getField("defaultMode").setInt(specs, modeId);
        float[] values = {physicalHz - 0.5f, physicalHz + 0.5f, renderHz - 0.5f, renderHz + 0.5f};
        if (optional(specs, "primaryRanges") != null) {
            for (String group : new String[]{"primaryRanges", "appRequestRanges"}) {
                Object ranges = field(specs, group);
                setRange(field(ranges, "physical"), values[0], values[1]);
                setRange(field(ranges, "render"), values[2], values[3]);
            }
        } else {
            // Android 12–13 layout: flat physical refresh-rate bounds.
            for (String group : new String[]{"primaryRefreshRate", "appRequestRefreshRate"}) {
                specs.getClass().getField(group + "Min").setFloat(specs, values[0]);
                specs.getClass().getField(group + "Max").setFloat(specs, values[1]);
            }
        }
        return specs;
    }

    private static void setRange(Object range, float min, float max) throws ReflectiveOperationException {
        range.getClass().getField("min").setFloat(range, min);
        range.getClass().getField("max").setFloat(range, max);
    }

    /** Policy as {primary physical min,max, primary render min,max, app physical min,max, app render min,max}. */
    private static float[] ranges(Object specs) throws ReflectiveOperationException {
        float[] out = new float[8];
        if (optional(specs, "primaryRanges") != null) {
            int i = 0;
            for (String group : new String[]{"primaryRanges", "appRequestRanges"}) {
                Object ranges = field(specs, group);
                for (String kind : new String[]{"physical", "render"}) {
                    Object range = field(ranges, kind);
                    out[i++] = (Float) field(range, "min");
                    out[i++] = (Float) field(range, "max");
                }
            }
        } else {
            float pMin = (Float) field(specs, "primaryRefreshRateMin"), pMax = (Float) field(specs, "primaryRefreshRateMax");
            float aMin = (Float) field(specs, "appRequestRefreshRateMin"), aMax = (Float) field(specs, "appRequestRefreshRateMax");
            out = new float[]{pMin, pMax, pMin, pMax, aMin, aMax, aMin, aMax};
        }
        return out;
    }

    /** True when the policy already holds exactly what {@link #pinnedSpecs} would write. */
    static boolean isPinned(Object specs, int modeId, float physicalHz, float renderHz)
            throws ReflectiveOperationException {
        if ((Integer) field(specs, "defaultMode") != modeId) return false;
        float[] r = ranges(specs);
        for (int i = 0; i < 8; i += 4) {
            if (!near(r[i], r[i + 1], physicalHz) || !near(r[i + 2], r[i + 3], renderHz)) return false;
        }
        return true;
    }

    private static boolean near(float min, float max, float hz) {
        return Math.abs(min - (hz - 0.5f)) < 0.3f && Math.abs(max - (hz + 0.5f)) < 0.3f;
    }

    static String signature(Object specs) throws ReflectiveOperationException {
        StringBuilder result = new StringBuilder().append(field(specs, "defaultMode"));
        for (float value : ranges(specs)) result.append('/').append(value);
        return result.toString();
    }

    // ---- SurfaceFlinger pacesetter -------------------------------------------------------

    static void forcePacesetter(long displayId) throws Exception {
        if (CODES == null) throw new IOException("This firmware has no pacesetter control.");
        surfaceFlingerCall(CODES.force(), displayId);
    }

    static void resetPacesetter() throws Exception {
        if (CODES == null) throw new IOException("This firmware has no pacesetter control.");
        surfaceFlingerCall(CODES.reset(), null);
    }

    private static void surfaceFlingerCall(int code, Long displayId) throws Exception {
        IBinder flinger = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "SurfaceFlingerAIDL");
        if (flinger == null) throw new IOException("SurfaceFlinger's AIDL service is unavailable.");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(flinger.getInterfaceDescriptor());
            if (displayId != null) data.writeLong(displayId);
            if (!flinger.transact(code, data, reply, 0)) throw new IOException("SurfaceFlinger ignored the timing request.");
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * SurfaceFlinger's own record of a forced pacesetter. {@code supported} is false when this
     * build's scheduler has no forced-pacesetter state. {@code display} is null when nothing is forced.
     */
    record Forced(boolean supported, Long display) {}

    static Forced forcedPacesetter() {
        try {
            for (String line : run(4, "/system/bin/dumpsys", "SurfaceFlinger", "--scheduler").split("\n")) {
                String text = line.trim();
                if (!text.startsWith("forcedPacesetterDisplayId=")) continue;
                String value = text.substring("forcedPacesetterDisplayId=".length()).trim();
                if (value.equals("nullopt") || value.isEmpty()) return new Forced(true, null);
                return new Forced(true, Long.parseUnsignedLong(value));
            }
        } catch (Exception ignored) {
            // Treated as unsupported below.
        }
        return new Forced(false, null);
    }

    /** Android's thermal status (0 none … 6 shutdown), or -1 when it can't be read. */
    static int thermalStatus() {
        try {
            for (String line : run(3, "/system/bin/dumpsys", "thermalservice").split("\n")) {
                String text = line.trim();
                if (text.startsWith("Thermal Status:")) return Integer.parseInt(text.substring(15).trim());
            }
        } catch (Exception ignored) {
            // Unknown.
        }
        return -1;
    }

    // ---- Samsung DeX ---------------------------------------------------------------------

    private static Object dexService() throws ReflectiveOperationException {
        Object taskManager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Object samsung = taskManager.getClass().getMethod("getMultiTaskingBinder").invoke(taskManager);
        if (samsung == null) throw new IllegalStateException("Samsung's DeX service is unavailable.");
        return samsung;
    }

    /** Samsung's switch that lifts DeX's external timing filter in the DisplayPort driver. */
    static void setHighResolution(boolean enabled) throws ReflectiveOperationException {
        Object samsung = dexService();
        samsung.getClass().getMethod("enableHighResolutionsForExternalDesktop", boolean.class).invoke(samsung, enabled);
    }

    // ---- helpers -------------------------------------------------------------------------

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Method find(Class<?> owner, String name, Class<?>... parameters) {
        if (owner == null) return null;
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** setDesiredDisplayModeSpecs(IBinder, specs) or (IBinder, specs[]), depending on the release. */
    private static Method findSetSpecs() {
        if (SC == null) return null;
        for (Method method : SC.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("setDesiredDisplayModeSpecs") && types.length == 2 && types[0] == IBinder.class) {
                return method;
            }
        }
        return null;
    }

    static Object field(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getField(name).get(owner);
    }

    private static Object optional(Object owner, String name) {
        try {
            return field(owner, name);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    static String run(int timeoutSeconds, String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // Drain output on its own thread so a hung command can't block past the timeout.
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(output);
            } catch (IOException ignored) {
                // The process was killed or closed its output.
            }
        }, "hzunlock-exec");
        reader.start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) throw new IOException(command[0] + " timed out.");
            reader.join(1_000);
            if (process.exitValue() != 0) throw new IOException(command[0] + " exited with " + process.exitValue() + ".");
            return output.toString(StandardCharsets.UTF_8).strip();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    static String describe(Throwable failure) {
        return Errors.describe(failure);
    }
}
