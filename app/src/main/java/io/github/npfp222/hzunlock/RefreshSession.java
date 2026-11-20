package io.github.npfp222.hzunlock;

import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sets the rate apps render at in DeX by choosing SurfaceFlinger's pacesetter display.
 *
 * <p>SurfaceFlinger ignores policy changes on an external display after it connects, but honors
 * them on the phone panel. So when the firmware lets HzUnlock force the pacesetter, the monitor's
 * full rate comes from making the monitor the pacesetter, and lower rates from pacing on the phone
 * panel held at one of its real modes. Without pacesetter control, the phone panel paces apps at
 * up to its own fastest mode, which still beats DeX's 60 Hz on most Samsung phones.
 */
final class RefreshSession {
    private static final long TICK_MS = 50;
    /** A monitor must stay connected this long before an automatic reconnect, so DeX can settle first. */
    private static final long AUTO_RECONNECT_DELAY_MS = 10_000;
    /** Never reconnect automatically more often than this, whatever the monitor does. */
    private static final long AUTO_RECONNECT_GAP_MS = 10 * 60_000;
    /** After a reconnect, DeX restarts and the monitor may drop out and return; treat that as the same plug-in. */
    private static final long RECONNECT_SETTLE_MS = 45_000;
    /** After a reconnect, wait this long for DeX to pick its mode before checking the rate it reached. */
    private static final long RECONNECT_CHECK_MS = 10_000;
    /** Re-applying DeX's high-refresh unlock on re-attach is limited to once in this window. */
    private static final long UNLOCK_GAP_MS = 60_000;
    /**
     * App timing stays with Android this long after a monitor appears, and after a reconnect ends,
     * so it's never forced onto a monitor that DeX is still dropping and re-adding.
     */
    private static final long ATTACH_SETTLE_MS = 3_000;
    private static final long TIMING_SETTLE_MS = 10_000;
    /** At or below this connection rate, moving app timing to the monitor gains nothing over DeX's 60 Hz. */
    private static final float DEX_RATE = 61f;
    /** How often to confirm SurfaceFlinger still has the timing source HzUnlock chose. */
    private static final long VERIFY_MS = 5_000;
    private static final float LOWEST_RATE = 24f;
    private static final String PROP = "vendor.display.hdmi_cfg_idx";
    private static final String DP_DEX = "/sys/class/dp_sec/dex";
    private static final String SHELL_LOCK = "/data/local/tmp/hzunlock.lock";
    private static final String ROOT_LOCK_DIR = "/data/adb/dexhz";

    private record Policy(IBinder token, Object original, String applied) {}

    private final boolean root;
    private final Map<Long, Policy> policies = new HashMap<>();
    private final Set<Long> seenMonitors = new HashSet<>();
    /** When each connected monitor was first seen in this plug-in. */
    private final Map<Long, Long> connectedSince = new HashMap<>();
    /** Monitors that already had their one automatic reconnect in this plug-in. */
    private final Set<Long> autoReconnected = new HashSet<>();
    /** Monitors that came back below their fastest mode after a reconnect → the rate they're stuck at. */
    private final Map<Long, Float> limitedLinks = new HashMap<>();
    /** What was last written to the event log about each monitor's mode, to log only changes. */
    private final Map<Long, String> loggedModes = new HashMap<>();
    private final Map<Long, String> monitorNames = new HashMap<>();
    private final EventLog log = new EventLog();
    private String loggedRate;
    private String loggedSettings;

    private ScheduledExecutorService worker;
    private RandomAccessFile shellLock;
    private RandomAccessFile rootLock;
    private Long pacesetter;
    /** True when HzUnlock must not (or can no longer) use the pacesetter transactions. */
    private boolean noPacesetter;
    private boolean pacesetterVerified;
    private String originalStartMode;
    private String writtenStartMode;
    private String notice;
    private float appHz;
    /** Set when a monitor (re)appears: SurfaceFlinger may have dropped the forced pacesetter on hotplug. */
    private boolean pacesetterStale;
    private long lastAutoReconnect;
    /** When a re-attached monitor (or a reconnect) last set DeX's high-refresh switch, or 0. */
    private volatile long lastUnlock;
    private volatile boolean reconnecting;
    /** When the last reconnect finished (elapsedRealtime), or 0. */
    private volatile long reconnectEndedAt;
    /** True after a reconnect until the rate it reached has been checked. */
    private volatile boolean checkReconnect;

    /** True while the session runs; a reconnect thread checks it before switching DeX's modes back on. */
    private volatile boolean active;
    /** True once this session switched on DeX's high-refresh modes, so turning off can switch them back. */
    private volatile boolean highResOn;
    /** False until the first tick, so monitors already connected at start don't wait to settle. */
    private boolean primed;
    /** True while app timing waits for a monitor to settle. */
    private boolean waiting;
    /** When the forced timing source was last read back from SurfaceFlinger. */
    private long verifiedAt;

    private volatile float targetHz = 1000f;
    private volatile boolean autoReconnect;
    private volatile boolean coolDown;

    /** Highest rate allowed by the phone's temperature; infinite when cool. */
    private float thermalCap = Float.MAX_VALUE;
    private int thermalStatus = -1;
    private long thermalCheckedAt;
    private long coolingSince;

    RefreshSession(boolean root) {
        this.root = root;
        // Use the timing transactions only if their numbers were found in this firmware's libgui
        // and SurfaceFlinger's scheduler reports the forced-pacesetter state they change.
        noPacesetter = !DisplayControl.hasPacesetterCalls() || !DisplayControl.forcedPacesetter().supported();
        thermalStatus = DisplayControl.thermalStatus();
    }

    EventLog log() {
        return log;
    }

    synchronized void configure(float target, boolean reconnect, boolean cool) {
        targetHz = target <= 0 ? 1000f : target;
        autoReconnect = reconnect;
        coolDown = cool;
        String settings = "Settings: rate " + (targetHz >= 1000f ? "fastest" : Math.round(targetHz) + " Hz")
                + ", Fix Slow Connections " + (reconnect ? "on" : "off") + ", Cool Down " + (cool ? "on" : "off");
        if (!settings.equals(loggedSettings)) {
            loggedSettings = settings;
            log.add(settings);
        }
        if (worker != null) worker.execute(this::tick);
    }

    synchronized String start(float target, boolean reconnect, boolean cool) {
        configure(target, reconnect, cool);
        if (worker != null) return "High refresh is on.";
        if (noPacesetter && !DisplayControl.canHoldPolicy()) {
            log.add("Couldn't start: no pacesetter calls and no display policy control.");
            return "This phone doesn't expose the display controls HzUnlock needs.";
        }
        try {
            shellLock = lock(new File(SHELL_LOCK));
            if (shellLock == null) return "Another HzUnlock instance is already controlling the display.";
            if (root) {
                File dir = new File(ROOT_LOCK_DIR);
                if (dir.isDirectory() || dir.mkdirs()) {
                    rootLock = lock(new File(dir, "hzlock.lock"));
                    if (rootLock == null) {
                        closeLocks();
                        return "The standalone HzLock root daemon is running. Stop it first.";
                    }
                }
                originalStartMode = readStartMode();
            }
            log.add("High refresh on. Monitor timing " + (noPacesetter ? "unavailable" : "available")
                    + " (calls " + DisplayControl.pacesetterCodes() + "), phone rates "
                    + (DisplayControl.canHoldPolicy() ? "available" : "unavailable") + ", " + (root ? "root" : "ADB shell") + ".");
            active = true;
            primed = false;
            unlockHighResolution("start");
            worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "hzunlock-session"));
            worker.scheduleWithFixedDelay(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
            return "High refresh is on.";
        } catch (Exception e) {
            if (worker != null) worker.shutdownNow();
            worker = null;
            active = false;
            closeLocks();
            log.add("Couldn't turn on high refresh: " + DisplayControl.describe(e));
            return "Couldn't turn on high refresh. " + DisplayControl.describe(e);
        }
    }

    /**
     * Ends the session and puts back Android's timing and refresh policies. With {@code restoreDex},
     * also switches DeX back to its standard modes when this session had switched on the faster
     * ones, which reconnects the monitor. That's the way back from a mode the phone can't drive,
     * which can leave the monitor black.
     */
    synchronized String stop(boolean restoreDex) {
        if (worker == null) return "High refresh is off.";
        worker.shutdownNow();
        worker = null;
        active = false;
        String problem = null;
        try {
            setPacesetter(null);
        } catch (Exception e) {
            problem = "the timing source";
        }
        for (Long id : new HashSet<>(policies.keySet())) {
            if (!release(id)) problem = "a display's refresh policy";
        }
        if (writtenStartMode != null) {
            try {
                if (writtenStartMode.equals(readStartMode())) writeStartMode(originalStartMode);
            } catch (Exception e) {
                problem = "the connection mode";
            }
        }
        writtenStartMode = null;
        if (restoreDex && highResOn && DisplayControl.hasHighResolutionSwitch()) {
            try {
                DisplayControl.setHighResolution(false);
                log.add("Switched DeX back to its standard modes.");
            } catch (Exception e) {
                problem = "DeX's standard modes";
                log.add("Couldn't switch DeX back to its standard modes: " + DisplayControl.describe(e));
            }
        }
        highResOn = false;
        waiting = false;
        notice = null;
        appHz = 0;
        pacesetter = null;
        pacesetterStale = false;
        seenMonitors.clear();
        connectedSince.clear();
        autoReconnected.clear();
        limitedLinks.clear();
        checkReconnect = false;
        loggedModes.clear();
        loggedRate = null;
        closeLocks();
        log.add(problem == null ? "High refresh off." : "High refresh off, but couldn't restore " + problem + ".");
        return problem == null ? "High refresh is off." : "High refresh is off, but HzUnlock couldn't restore " + problem + ".";
    }

    private void unlockHighResolution(String reason) {
        if (!DisplayControl.hasHighResolutionSwitch()) return;
        try {
            DisplayControl.setHighResolution(true);
            highResOn = true;
            lastUnlock = SystemClock.elapsedRealtime();
            log.add("Switched on DeX's high-refresh modes (" + reason + ").");
        } catch (Exception e) {
            // Older DeX builds expose high-refresh modes without the switch.
            log.add("DeX's high-refresh switch failed (" + reason + "): " + DisplayControl.describe(e));
        }
    }

    // ---- control loop --------------------------------------------------------------------

    private synchronized void tick() {
        if (worker == null) return;
        try {
            long now = SystemClock.elapsedRealtime();
            List<DisplayControl.Info> displays = DisplayControl.displays();
            DisplayControl.Info phone = phone(displays);
            DisplayControl.Info lead = lead(displays);
            Set<Long> present = new HashSet<>();
            boolean newMonitor = false;
            for (DisplayControl.Info display : displays) {
                present.add(display.id());
                if (display.internal()) continue;
                String mode = describe(display.active());
                if (seenMonitors.add(display.id())) {
                    newMonitor = true;
                    monitorNames.put(display.id(), display.name());
                    log.add("Monitor connected: " + display.name() + " " + mode + " (fastest "
                            + Math.round(display.fastest().hz()) + " Hz, " + display.modes().size() + " modes).");
                } else if (!mode.equals(loggedModes.get(display.id()))) {
                    log.add("Monitor mode changed: " + display.name() + " " + mode + ".");
                }
                loggedModes.put(display.id(), mode);
                // A monitor already connected when the session starts has nothing left to settle.
                connectedSince.putIfAbsent(display.id(), primed ? now : now - ATTACH_SETTLE_MS);
            }
            primed = true;
            for (Long id : seenMonitors) {
                if (!present.contains(id)) {
                    log.add("Monitor disconnected: " + monitorNames.getOrDefault(id, "external display") + ".");
                    loggedModes.remove(id);
                }
            }
            seenMonitors.retainAll(present);
            // A reconnect restarts DeX, and the monitor can drop out and come back while it does.
            // Only an absence outside that window counts as an unplug, which allows one new
            // automatic reconnect and clears the "limited connection" finding.
            boolean settling = settling(now);
            if (!settling) {
                connectedSince.keySet().retainAll(present);
                autoReconnected.retainAll(present);
                limitedLinks.keySet().retainAll(present);
            }
            if (newMonitor) {
                pacesetterStale = true;
                // A newly attached monitor may need DeX's high-refresh modes unlocked again. A
                // reconnect sets the switch itself, and repeated calls are rate-limited, so this
                // can never drive a restart loop.
                if (!settling && (lastUnlock == 0 || now - lastUnlock > UNLOCK_GAP_MS)) {
                    lastUnlock = now;
                    unlockHighResolution("monitor attached");
                }
            }
            policies.keySet().retainAll(present);
            for (Long id : new HashSet<>(policies.keySet())) {
                if (phone == null || id != phone.id()) release(id);
            }
            updateThermal();

            if (lead == null) {
                // No monitor: leave the phone to Android.
                if (phone != null) release(phone.id());
                setPacesetter(null);
                appHz = 0;
                waiting = false;
                logRate("No monitor; the phone runs at Android's own rates.");
            } else if (timingOnHold(lead, now)) {
                // DeX may still drop and re-add the monitor. Keep app timing with Android until it
                // settles, so SurfaceFlinger never loses a forced timing source mid-change.
                if (phone != null) release(phone.id());
                setPacesetter(null);
                appHz = 0;
                waiting = true;
                logRate("Waiting for the monitor to settle; app timing stays with Android.");
            } else {
                waiting = false;
                float link = lead.active().hz();
                float want = effectiveTarget();
                boolean throttled = want < targetHz;
                DisplayControl.Mode phoneMode = null;
                if (phone != null && DisplayControl.canHoldPolicy() && (noPacesetter || want < link - 1f)) {
                    // Without pacesetter control the phone panel always paces, up to the monitor's rate.
                    float ceiling = noPacesetter ? link + 1f : link - 1f;
                    phoneMode = phoneModeFor(phone, want, ceiling);
                }
                if (phoneMode != null) {
                    hold(phone.id(), DisplayControl.token(phone.id()), phoneMode);
                    setPacesetter(phone.id());
                    appHz = phoneMode.hz();
                    logRate(String.format(Locale.US, "Apps run at %d Hz, paced by the phone screen%s.",
                            Math.round(appHz), throttled ? ", lowered for heat" : ""));
                } else if (link <= DEX_RATE) {
                    // A 60 Hz connection gains nothing from moving app timing to the monitor, so
                    // leave it with Android rather than force a timing source that can disappear.
                    if (phone != null) release(phone.id());
                    setPacesetter(null);
                    appHz = link;
                    logRate(String.format(Locale.US, "Monitor at %d Hz; app timing stays with Android.", Math.round(link)));
                } else {
                    if (phone != null) release(phone.id());
                    setPacesetter(lead.id());
                    appHz = noPacesetter ? (phone != null ? phone.active().hz() : 60f) : link;
                    logRate(String.format(Locale.US, "Apps run at %d Hz, paced by the %s.", Math.round(appHz),
                            noPacesetter ? "phone screen (no monitor timing)" : "monitor"));
                }
            }
            if (lead != null) {
                checkAfterReconnect(lead, now);
                if (needsReconnect(lead)) onSlowConnection(lead, now);
            }
            verifyPacesetter(now);
            if (notice != null && notice.startsWith("Display update failed")) notice = null;
        } catch (Throwable e) {
            // Catch errors too: an uncaught one would silently cancel the scheduled tick.
            notice = "Display update failed: " + DisplayControl.describe(e);
            log.add(notice);
        }
    }

    private void logRate(String text) {
        if (text.equals(loggedRate)) return;
        loggedRate = text;
        log.add(text);
    }

    private static String describe(DisplayControl.Mode mode) {
        return mode.width() + "x" + mode.height() + " @ " + Math.round(mode.hz()) + " Hz";
    }

    /** The phone panel that is switched on (foldables have two), else the first internal panel. */
    private static DisplayControl.Info phone(List<DisplayControl.Info> displays) {
        DisplayControl.Info first = null;
        for (DisplayControl.Info display : displays) {
            if (!display.internal()) continue;
            if (display.on()) return display;
            if (first == null) first = display;
        }
        return first;
    }

    /** The external display running at the highest rate, or null when no monitor is connected. */
    private static DisplayControl.Info lead(List<DisplayControl.Info> displays) {
        DisplayControl.Info lead = null;
        for (DisplayControl.Info display : displays) {
            if (!display.internal() && (lead == null || display.active().hz() > lead.active().hz())) lead = display;
        }
        return lead;
    }

    /**
     * The phone panel's fastest real mode at its current resolution between 24 Hz and
     * {@code ceiling} that doesn't exceed the target, else its slowest one in that range. Rates
     * that don't divide the monitor's rate evenly are allowed: frames then stay up for an uneven
     * number of monitor refreshes, like 60 fps video on a 144 Hz monitor, which beats having no
     * choice between 288 and 48 Hz. Returns null when the panel has no mode in range.
     */
    private static DisplayControl.Mode phoneModeFor(DisplayControl.Info phone, float target, float ceiling) {
        DisplayControl.Mode best = null;
        DisplayControl.Mode slowest = null;
        for (DisplayControl.Mode mode : phoneRates(phone, ceiling)) {
            if (slowest == null || mode.hz() < slowest.hz()) slowest = mode;
            if (mode.hz() <= target + 0.5f && (best == null || mode.hz() > best.hz())) best = mode;
        }
        return best != null ? best : slowest;
    }

    /** The phone panel's real modes at its current resolution between 24 Hz and {@code ceiling}. */
    private static List<DisplayControl.Mode> phoneRates(DisplayControl.Info phone, float ceiling) {
        List<DisplayControl.Mode> rates = new ArrayList<>();
        for (DisplayControl.Mode mode : phone.modes()) {
            if (mode.id() < 0) continue;
            if (mode.width() != phone.active().width() || mode.height() != phone.active().height()) continue;
            if (mode.hz() < LOWEST_RATE - 0.5f || mode.hz() > ceiling) continue;
            rates.add(mode);
        }
        return rates;
    }

    /** True while app timing should stay with Android because DeX may still drop and re-add the monitor. */
    private boolean timingOnHold(DisplayControl.Info lead, long now) {
        if (reconnecting) return true;
        long ended = reconnectEndedAt;
        if (ended != 0 && now - ended < TIMING_SETTLE_MS) return true;
        Long since = connectedSince.get(lead.id());
        return since != null && now - since < ATTACH_SETTLE_MS;
    }

    /** True when {@code hz} divides {@code linkHz} into a whole number of refreshes. */
    static boolean smooth(float linkHz, float hz) {
        float ratio = linkHz / hz;
        long whole = Math.round(ratio);
        return whole >= 1 && Math.abs(ratio - whole) < 0.02f;
    }

    private float effectiveTarget() {
        return coolDown ? Math.min(targetHz, thermalCap) : targetHz;
    }

    /** Lowers the rate at once when the phone heats up; raises it again after 30 s of cooler readings. */
    private void updateThermal() {
        long now = SystemClock.elapsedRealtime();
        if (now - thermalCheckedAt < 2_000) return;
        thermalCheckedAt = now;
        int status = DisplayControl.thermalStatus();
        if (status != thermalStatus) log.add("Thermal status " + thermalStatus + " -> " + status + ".");
        thermalStatus = status;
        float cap = thermalStatus >= 3 ? 60f : thermalStatus == 2 ? 120f : Float.MAX_VALUE;
        if (cap < thermalCap) {
            thermalCap = cap;
            coolingSince = 0;
        } else if (cap > thermalCap) {
            if (coolingSince == 0) coolingSince = now;
            else if (now - coolingSince > 30_000) {
                thermalCap = cap;
                coolingSince = 0;
            }
        } else {
            coolingSince = 0;
        }
    }

    /** Pins a display's default mode and both ranges to {@code mode} so Android can't move it. */
    private void hold(long id, IBinder token, DisplayControl.Mode mode) throws Exception {
        if (token == null) return;
        Object current = DisplayControl.readSpecs(token);
        if (DisplayControl.isPinned(current, mode.id(), mode.hz(), mode.hz())) return;
        DisplayControl.writeSpecs(token, DisplayControl.pinnedSpecs(token, mode.id(), mode.hz(), mode.hz()));
        Object applied = DisplayControl.readSpecs(token);
        if (!DisplayControl.isPinned(applied, mode.id(), mode.hz(), mode.hz())) {
            throw new IOException("SurfaceFlinger didn't keep " + Math.round(mode.hz()) + " Hz.");
        }
        Policy previous = policies.get(id);
        // Keep Android's own policy for restoring; never record one of ours as the original.
        Object original = previous != null && previous.token().equals(token)
                && previous.applied().equals(DisplayControl.signature(current)) ? previous.original() : current;
        policies.put(id, new Policy(token, original, DisplayControl.signature(applied)));
    }

    /** Puts back Android's policy if ours is still in place. Returns false when the restore failed. */
    private boolean release(long id) {
        Policy policy = policies.remove(id);
        if (policy == null) return true;
        try {
            if (policy.token().equals(DisplayControl.token(id))
                    && policy.applied().equals(DisplayControl.signature(DisplayControl.readSpecs(policy.token())))) {
                DisplayControl.writeSpecs(policy.token(), policy.original());
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Forces (or clears) SurfaceFlinger's pacesetter, then reads SurfaceFlinger's own state back.
     * A mismatch means the calls don't behave as expected on this firmware, so HzUnlock stops using
     * them and falls back to pacing from the phone panel.
     */
    private void setPacesetter(Long display) throws Exception {
        if (noPacesetter || (!pacesetterStale && Objects.equals(pacesetter, display))) return;
        pacesetterStale = false;
        try {
            if (display == null) DisplayControl.resetPacesetter();
            else DisplayControl.forcePacesetter(display);
        } catch (Exception e) {
            disablePacesetter();
            return;
        }
        DisplayControl.Forced after = DisplayControl.forcedPacesetter();
        if (!after.supported() || !Objects.equals(after.display(), display)) {
            disablePacesetter();
            return;
        }
        pacesetter = display;
        pacesetterVerified = true;
    }

    /**
     * Every few seconds, confirms that SurfaceFlinger still uses the timing source HzUnlock chose.
     * DeX, SurfaceFlinger itself, or another tool can change it; when that happens, HzUnlock
     * applies its choice again on the next tick and notes it in the event log.
     */
    private void verifyPacesetter(long now) {
        if (noPacesetter || pacesetterStale || now - verifiedAt < VERIFY_MS) return;
        verifiedAt = now;
        DisplayControl.Forced forced = DisplayControl.forcedPacesetter();
        if (!forced.supported() || Objects.equals(forced.display(), pacesetter)) return;
        log.add("App timing was moved outside HzUnlock (now " + (forced.display() == null ? "Android's choice"
                : forced.display()) + "); setting it again.");
        pacesetterStale = true;
    }

    private void disablePacesetter() {
        log.add("SurfaceFlinger didn't confirm the forced pacesetter; pacing from the phone screen from now on.");
        noPacesetter = true;
        pacesetter = null;
        notice = "This phone didn't accept moving app timing to the monitor, so HzUnlock paces apps from the phone screen.";
    }

    /**
     * True when the monitor connected below its fastest mode, the target wants more, and a
     * reconnect hasn't already shown that this connection can't go faster.
     */
    private boolean needsReconnect(DisplayControl.Info display) {
        return display.belowFastest() && targetHz > display.active().hz() + 1f && !limitedLinks.containsKey(display.id());
    }

    /** True while a reconnect runs and for a while after, when DeX may drop and re-add the monitor. */
    private boolean settling(long now) {
        long ended = reconnectEndedAt;
        return reconnecting || (ended != 0 && now - ended < RECONNECT_SETTLE_MS);
    }

    /**
     * Once DeX has settled after a reconnect, records whether the monitor reached its fastest
     * mode. If it came back below it again, the hub, cable, or DeX can't carry that mode, so
     * HzUnlock stops trying for this plug-in and says so.
     */
    private void checkAfterReconnect(DisplayControl.Info display, long now) {
        if (!checkReconnect || reconnecting || now - reconnectEndedAt < RECONNECT_CHECK_MS) return;
        Long since = connectedSince.get(display.id());
        if (since != null && now - since < RECONNECT_CHECK_MS) return;
        checkReconnect = false;
        if (display.belowFastest()) {
            limitedLinks.put(display.id(), display.active().hz());
            log.add("After reconnecting: still " + describe(display.active()) + " (fastest " + Math.round(display.fastest().hz())
                    + " Hz). The connection tops out here; no more automatic reconnects for this plug-in.");
        } else {
            limitedLinks.remove(display.id());
            log.add("After reconnecting: " + describe(display.active()) + ".");
        }
    }

    private void onSlowConnection(DisplayControl.Info display, long now) {
        if (root) {
            String mode = display.fastest().width() + ":" + display.fastest().height() + ":" + Math.round(display.fastest().hz());
            try {
                if (!mode.equals(readStartMode())) {
                    writeStartMode(mode);
                    writtenStartMode = mode;
                }
            } catch (Exception ignored) {
                // A physical replug still renegotiates the link.
            }
        }
        // One automatic attempt per plug-in, only after the monitor has been up for a while,
        // never while a reconnect is settling, and never more than once per gap overall.
        if (!autoReconnect || checkReconnect || settling(now) || autoReconnected.contains(display.id())) return;
        Long since = connectedSince.get(display.id());
        if (since == null || now - since < AUTO_RECONNECT_DELAY_MS) return;
        if (lastAutoReconnect != 0 && now - lastAutoReconnect < AUTO_RECONNECT_GAP_MS) return;
        autoReconnected.add(display.id());
        lastAutoReconnect = now;
        reconnect("automatic, monitor at " + Math.round(display.active().hz()) + " of "
                + Math.round(display.fastest().hz()) + " Hz");
    }

    // ---- reconnect -----------------------------------------------------------------------

    /** Reconnects the monitor because the user asked to. */
    String reconnect() {
        return reconnect("manual");
    }

    private synchronized String reconnect(String reason) {
        if (worker == null) return "Turn on High Refresh first.";
        if (reconnecting) return "Already reconnecting.";
        if (!(root && new File(DP_DEX).exists()) && !DisplayControl.hasHighResolutionSwitch()) {
            log.add("Reconnect (" + reason + ") unavailable: no DeX high-refresh switch.");
            return "Unplug the monitor and plug it back in.";
        }
        log.add("Reconnecting the monitor (" + reason + ").");
        reconnecting = true;
        checkReconnect = false;
        // Move app timing off the monitor before DeX drops it, rather than after.
        try {
            setPacesetter(null);
        } catch (Exception e) {
            log.add("Couldn't release app timing before reconnecting: " + DisplayControl.describe(e));
        }
        Thread thread = new Thread(() -> {
            boolean switchedOff = false;
            try {
                if (root && new File(DP_DEX).exists()) {
                    write(DP_DEX, "17");
                    SystemClock.sleep(6_000);
                    write(DP_DEX, "0");
                } else {
                    DisplayControl.setHighResolution(false);
                    switchedOff = true;
                    SystemClock.sleep(5_000);
                    // If high refresh was turned off meanwhile, DeX stays in its standard modes.
                    if (active) {
                        DisplayControl.setHighResolution(true);
                        highResOn = true;
                    }
                    switchedOff = false;
                }
            } catch (Exception e) {
                notice = "Reconnect failed. Unplug the monitor and plug it back in.";
                log.add("Reconnect failed: " + DisplayControl.describe(e));
            } finally {
                // Never leave DeX's high-refresh modes switched off while high refresh is on.
                if (switchedOff && active) {
                    try {
                        DisplayControl.setHighResolution(true);
                    } catch (Exception ignored) {
                        // Reported above; a replug restores DeX's defaults.
                    }
                }
                SystemClock.sleep(4_000);
                reconnectEndedAt = SystemClock.elapsedRealtime();
                lastUnlock = reconnectEndedAt;
                checkReconnect = true;
                reconnecting = false;
                log.add("Reconnect finished.");
            }
        }, "hzunlock-reconnect");
        thread.start();
        return "Reconnecting. The monitor goes dark for a few seconds.";
    }

    // ---- state ---------------------------------------------------------------------------

    synchronized JSONObject state() throws JSONException {
        JSONObject json = new JSONObject();
        boolean running = worker != null;
        json.put("running", running);
        json.put("reconnecting", reconnecting);
        if (running && waiting) json.put("waiting", true);
        json.put("targetHz", targetHz);
        if (running && appHz > 0) json.put("appHz", appHz);
        if (running && coolDown && thermalCap < targetHz) json.put("coolingHz", thermalCap);
        if (notice != null) json.put("notice", notice);

        JSONObject caps = new JSONObject();
        caps.put("highRes", DisplayControl.hasHighResolutionSwitch());
        caps.put("monitorTiming", !noPacesetter);
        caps.put("monitorTimingVerified", pacesetterVerified);
        caps.put("phoneRates", DisplayControl.canHoldPolicy());
        caps.put("heat", thermalStatus >= 0);
        caps.put("codes", DisplayControl.pacesetterCodes());
        caps.put("root", root);
        json.put("caps", caps);

        try {
            List<DisplayControl.Info> displays = DisplayControl.displays();
            DisplayControl.Info phone = phone(displays);
            DisplayControl.Info lead = lead(displays);
            if (lead != null) {
                float link = lead.active().hz();
                TreeSet<Integer> stops = new TreeSet<>();
                if (!noPacesetter) stops.add(Math.round(link));
                if (phone != null && DisplayControl.canHoldPolicy()) {
                    for (DisplayControl.Mode mode : phoneRates(phone, noPacesetter ? link + 1f : link - 1f)) {
                        stops.add(Math.round(mode.hz()));
                    }
                }
                if (stops.isEmpty()) stops.add(Math.round(noPacesetter && phone != null ? phone.fastest().hz() : link));
                // Only rates the current connection really runs at. A faster mode the monitor lists
                // but didn't connect in is offered through Reconnect Monitor, not as a stop.
                json.put("stops", new JSONArray(stops));

                JSONObject monitor = new JSONObject();
                monitor.put("name", lead.name());
                monitor.put("width", lead.active().width());
                monitor.put("height", lead.active().height());
                monitor.put("linkHz", link);
                monitor.put("fastestHz", lead.fastest().hz());
                monitor.put("needsReconnect", running && needsReconnect(lead));
                Float limited = limitedLinks.get(lead.id());
                if (limited != null && lead.belowFastest()) monitor.put("limitedHz", limited);
                describeModes(lead, monitor);
                json.put("monitor", monitor);
            }
        } catch (Exception e) {
            json.put("notice", "Couldn't read the displays: " + DisplayControl.describe(e));
        }
        return json;
    }

    /**
     * Adds what the monitor offers over this connection: the fastest rate at each resolution
     * (for the compatibility report), the rates at the current resolution, the fastest rate at
     * any resolution, and the best faster mode at a lower resolution, if there is one.
     */
    private static void describeModes(DisplayControl.Info display, JSONObject out) throws JSONException {
        DisplayControl.Mode active = display.active();
        Map<String, Integer> fastestBySize = new LinkedHashMap<>();
        List<DisplayControl.Mode> sorted = new ArrayList<>(display.modes());
        sorted.sort((a, b) -> Long.compare((long) b.width() * b.height(), (long) a.width() * a.height()));
        TreeSet<Integer> activeRates = new TreeSet<>();
        int maxHz = 0;
        DisplayControl.Mode lower = null;
        long activePixels = (long) active.width() * active.height();
        for (DisplayControl.Mode mode : sorted) {
            int hz = Math.round(mode.hz());
            fastestBySize.merge(mode.width() + "x" + mode.height(), hz, Math::max);
            if (mode.width() == active.width() && mode.height() == active.height()) activeRates.add(hz);
            maxHz = Math.max(maxHz, hz);
            if ((long) mode.width() * mode.height() < activePixels && mode.hz() > active.hz() + 1f
                    && (lower == null || mode.hz() > lower.hz() + 1f)) {
                lower = mode;
            }
        }
        StringBuilder modes = new StringBuilder();
        for (Map.Entry<String, Integer> entry : fastestBySize.entrySet()) {
            if (modes.length() > 0) modes.append(", ");
            modes.append(entry.getKey()).append(" @ ").append(entry.getValue());
        }
        out.put("modes", modes.toString());
        out.put("rates", new JSONArray(activeRates));
        out.put("maxHz", maxHz);
        if (lower != null) {
            JSONObject hint = new JSONObject();
            hint.put("width", lower.width());
            hint.put("height", lower.height());
            hint.put("hz", lower.hz());
            out.put("lowerRes", hint);
        }
    }

    // ---- root-only connect-time mode -------------------------------------------------------

    private static String readStartMode() throws Exception {
        return DisplayControl.run(2, "/system/bin/getprop", PROP);
    }

    private static void writeStartMode(String value) throws Exception {
        String target = value == null ? "" : value;
        try {
            DisplayControl.run(2, "/system/bin/setprop", PROP, target);
        } catch (IOException ignored) {
            // vendor_display_prop may need resetprop from an existing root environment.
        }
        if (target.equals(readStartMode())) return;
        for (String helper : new String[]{"/data/adb/ksud", ROOT_LOCK_DIR + "/ksud", "/data/adb/magisk/resetprop"}) {
            if (!new File(helper).canExecute()) continue;
            if (helper.endsWith("resetprop")) DisplayControl.run(2, helper, PROP, target);
            else DisplayControl.run(2, helper, "resetprop", PROP, target);
            if (target.equals(readStartMode())) return;
        }
        throw new IOException("The vendor start-mode property rejected the write.");
    }

    private static void write(String path, String value) throws IOException {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(value);
        }
    }

    private static RandomAccessFile lock(File file) throws IOException {
        RandomAccessFile handle = new RandomAccessFile(file, "rw");
        FileLock lock = handle.getChannel().tryLock();
        if (lock != null) return handle;
        handle.close();
        return null;
    }

    private void closeLocks() {
        for (Closeable handle : new Closeable[]{shellLock, rootLock}) {
            if (handle == null) continue;
            try {
                handle.close();
            } catch (IOException ignored) {
                // Closing releases the process lock either way.
            }
        }
        shellLock = null;
        rootLock = null;
    }
}
