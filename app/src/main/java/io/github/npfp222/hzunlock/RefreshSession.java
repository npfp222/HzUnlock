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
    /** A fast connection Android removes within this long of starting counts as a lost link. */
    private static final long SHORT_LINK_MS = 20_000;
    /** How often to confirm SurfaceFlinger still has the timing source HzUnlock chose. */
    private static final long VERIFY_MS = 5_000;
    private static final float LOWEST_RATE = 24f;
    /**
     * Qualcomm's composer reads this {@code width:height:fps} string on every DisplayPort connect
     * and starts the monitor in the matching mode. Only write a validated timing and clear it once
     * SurfaceFlinger has seen the new connection.
     */
    private static final String PROP = "vendor.display.hdmi_cfg_idx";
    private static final String DP_DEX = "/sys/class/dp_sec/dex";
    private static final String DP_OFF = "ExtDispOff,1";
    private static final String DP_ON = "ExtDispOff,0";
    private static final String SHELL_LOCK = "/data/local/tmp/hzunlock.lock";
    private static final String ROOT_LOCK_DIR = "/data/adb/dexhz";
    /** Remembers the value HzUnlock wrote to {@link #PROP}, so a crashed service's value is cleared on the next start. */
    private static final File PROP_MARK = new File(ROOT_LOCK_DIR, "hzunlock-start-mode.txt");
    private static final long MODE_DISCONNECT_MS = 4_000;
    /** After a mode switch's reconnect, how long the monitor has to come back before the switch is undone. */
    private static final long MODE_RETURN_MS = 10_000;
    /** How long the user has to keep a switched mode before it's undone (the screen may be black). */
    private static final long MODE_CONFIRM_MS = 20_000;
    /** The start mode the user kept without root, as {@link StartModeRacer.Target#serialize}. Shell and root can write here. */
    private static final File START_FILE = new File("/data/local/tmp/hzunlock-start-mode.txt");
    /** After a start-mode reconnect, how long to wait for the composer to report the new connection. */
    private static final long START_MATCH_MS = 6_000;
    /** SurfaceFlinger adds the display and DeX settles after the composer; judge a start mode this long after. */
    private static final long START_JUDGE_MS = 1_500;
    /** How long the user has to unplug and replug a monitor that DeX's reconnect didn't reconnect. */
    private static final long REPLUG_MS = 60_000;
    /** Rescue: wait this long for DeX to re-add a dropped monitor by itself before reconnecting it. */
    private static final long RESCUE_WAIT_MS = 8_000;
    /** A connection lost at or above this rate gets an automatic half-rate start mode to keep. */
    private static final float RESCUE_MIN_HZ = 200f;
    /** A working connection at or above this rate is close to the phone's link limit; half of it is suggested. */
    private static final float NEAR_LIMIT_HZ = 270f;
    /** A suggested mode is offered only once DeX has finished reconnecting and re-adding the monitor. */
    private static final long SUGGEST_SETTLE_MS = 15_000;
    /** Smaller modes are left out of the choices: DeX on them is no use (800 × 600 at 75 Hz, say). */
    private static final long MIN_CHOICE_PIXELS = 1280L * 720;

    /**
     * A monitor that Android removes this soon after HzUnlock moved app timing onto it is timed
     * from the phone screen from then on, before the link-loss recovery counts the drop.
     */
    private static final long PACED_DROP_MS = 10_000;
    /**
     * Some monitors drop once on their own seconds after connecting (an Odyssey G40B does, with
     * app timing still on Android). After a first such drop, app timing goes back to the monitor
     * once it has stayed connected this long; only a second drop keeps it on the phone screen.
     */
    private static final long PACED_RETRY_MS = 15_000;

    private record Policy(IBinder token, Object original, String applied) {}

    /**
     * A rate the phone screen can pace apps at: one of its real modes, with apps drawing at that
     * rate or at a whole fraction of it (120 Hz drawing at 60, 40, 30 or 24). The fraction gives
     * the slider rates the panel has no mode for.
     */
    record PhoneRate(DisplayControl.Mode mode, float hz) {
        boolean capped() {
            return hz < mode.hz() - 0.5f;
        }
    }

    /** A monitor mode the user chose for the root connect-time switch, tied to one connected monitor. */
    record ModeTarget(long display, int width, int height, int hz) {
        String key() {
            return width + ":" + height + ":" + hz;
        }

        String label() {
            return width + " × " + height + " at " + hz + " Hz";
        }
    }

    /**
     * SWITCHING: the monitor is being reconnected into the mode. REPLUG (start mode without root):
     * DeX's reconnect didn't reconnect it, so the user is asked to replug it. CONFIRM: the monitor
     * is in the mode and the user has {@link #MODE_CONFIRM_MS} to keep it.
     */
    private enum ModePhase { SWITCHING, REPLUG, CONFIRM }

    /**
     * Recognizes a high-refresh connection that Android removes soon after it came up, which is
     * how a mode the monitor, hub, or cable can't hold looks from the phone. Only removals
     * SurfaceFlinger sees count: a monitor that goes dark while Android keeps it connected isn't
     * visible here, and neither is a drop to a slower mode on the same connection.
     *
     * <p>The first such loss holds the monitor: HzUnlock stops switching DeX's faster modes back
     * on for it and stops reconnecting it automatically, so HzUnlock itself never retries the
     * mode. A second loss while held means DeX or the driver re-entered the mode on its own.
     */
    static final class LinkDropTracker {
        enum Event { NONE, LOST, REPEATED }

        private Long fastMonitor;
        private float fastHz;
        private long fastSince;
        private float lastLostHz;
        /** Monitors whose fast connection was lost soon after it started → that connection's rate. */
        private final Map<Long, Float> lost = new HashMap<>();

        /**
         * @param leadId the fastest connected monitor, or null
         * @param present every display SurfaceFlinger lists now
         * @param lastUnlock when HzUnlock last switched DeX's faster modes on; a connection that
         *                   was already up then may be re-added by DeX in answer to that call
         */
        Event observe(Long leadId, float leadHz, Set<Long> present, long now, long lastUnlock) {
            Long previous = fastMonitor;
            float previousHz = fastHz;
            long since = fastSince;
            if (leadId != null && leadHz > DEX_RATE) {
                if (!leadId.equals(previous)) fastSince = now;
                fastMonitor = leadId;
                fastHz = leadHz;
            } else {
                fastMonitor = null;
            }
            if (previous == null || present.contains(previous)) return Event.NONE;
            if (since <= lastUnlock || now - since > SHORT_LINK_MS) return Event.NONE;
            lastLostHz = previousHz;
            return lost.put(previous, previousHz) != null ? Event.REPEATED : Event.LOST;
        }

        /** Stops watching the current connection, keeping what was already lost (DeX is busy reconnecting). */
        void pause() {
            fastMonitor = null;
        }

        boolean held(long displayId) {
            return lost.containsKey(displayId);
        }

        Float lostHz(long displayId) {
            return lost.get(displayId);
        }

        float lastLostHz() {
            return lastLostHz;
        }

        void reset() {
            fastMonitor = null;
            lost.clear();
        }
    }

    private final boolean root;
    /** Qualcomm's display config service and the start-mode racer on it; null without the service. */
    private final DisplayConfig displayConfig;
    private final StartModeRacer racer;
    /** The start mode the user kept without root: set on every connect of the monitor it was chosen on. */
    private volatile StartModeRacer.Target keptStart;
    /** The start mode of the switch in progress (no root), else null. */
    private volatile StartModeRacer.Target switchStart;
    /** {@link StartModeRacer#matches()} when the switch (or its latest try) began. */
    private int switchBaseline;
    private long switchStartedAt;
    /** True once the switch's DeX reconnect has been started. */
    private boolean switchToggled;
    private int switchTries;
    /** True when the switch is an automatic rescue after a lost high-refresh connection. */
    private boolean switchRescue;
    /**
     * Until when a monitor that left after starting in the target mode may come back. DeX
     * disconnects and re-adds the monitor about 8 s after its high-res switch reconnects it
     * (seen on the S24 Ultra after every off/on, with or without a start mode); the racer sets the
     * mode again on that connection.
     */
    private long switchRejoinUntil;
    /** The last monitor seen, for a rescue after Android removed it. */
    private DisplayControl.Info lastLead;
    /** A kept start mode's connection, to check once SurfaceFlinger and DeX have the monitor. */
    private StartModeRacer.Attempt pendingStart;
    private final LinkDropTracker linkDrops = new LinkDropTracker();
    private final Map<Long, Policy> policies = new HashMap<>();
    private final Set<Long> seenMonitors = new HashSet<>();
    /** When each connected monitor was first seen in this plug-in. */
    private final Map<Long, Long> connectedSince = new HashMap<>();
    /** Monitors that already had their one automatic reconnect in this plug-in. */
    private final Set<Long> autoReconnected = new HashSet<>();
    /** Monitors that came back below their fastest mode after a reconnect → the rate they're stuck at. */
    private final Map<Long, Float> limitedLinks = new HashMap<>();
    /** Monitors that already had a reconnect checked in this plug-in. */
    private final Set<Long> reconnectedLinks = new HashSet<>();
    /** Monitors whose root-chosen mode the user kept in this plug-in → that mode's key. */
    private final Map<Long, String> keptModes = new HashMap<>();
    /** Monitors Android removed right after taking app timing; the phone screen paces apps for them. */
    private final Set<Long> phonePaced = new HashSet<>();
    /** Monitors that already got app timing back after such a drop; another drop is final. */
    private final Set<Long> pacedRetried = new HashSet<>();
    /** The monitor HzUnlock last moved app timing onto, and when (elapsedRealtime). */
    private Long pacedMonitor;
    private long pacedSince;
    /** True once SurfaceFlinger refused to draw apps below the phone panel's own rate. */
    private volatile boolean noRenderCaps;
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
    /** The value HzUnlock last wrote to {@link #PROP}, while it may still be in place. */
    private volatile String writtenStartMode;
    /** The root mode switch in progress or waiting for the user to keep it, else null. */
    private volatile ModeTarget modeTarget;
    private volatile ModePhase modePhase;
    /** When the current phase of the mode switch times out (elapsedRealtime). */
    private volatile long modeDeadline;
    /** The outcome of the last mode switch, shown until the next one. */
    private volatile String modeResult;
    /** The switch's mode as the UI names it: "144 Hz", or the size too when that changes. */
    private volatile String modeShortLabel;
    /** {@link #fastestKey} of the monitor the switch is for, to remember a mode that didn't work. */
    private volatile String modeMonitorKey;
    /** Modes that were undone or dropped, as {@code fastestKey|modeKey}; never suggested again in this service. */
    private final Set<String> failedModes = new HashSet<>();
    /** Why the session last turned itself off to recover the picture; cleared on the next start. */
    private volatile String rolledBack;
    /** True when the last reconnect threw before it finished. */
    private volatile boolean reconnectFailed;
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
        displayConfig = DisplayConfig.open();
        racer = displayConfig != null ? new StartModeRacer(displayConfig) : null;
        keptStart = loadStart();
        // Use the timing transactions only if their numbers were found in this firmware's libgui
        // and SurfaceFlinger's scheduler reports the forced-pacesetter state they change.
        noPacesetter = !DisplayControl.hasPacesetterCalls() || !DisplayControl.forcedPacesetter().supported();
        thermalStatus = DisplayControl.thermalStatus();
    }

    /**
     * A service that died mid-switch can leave its value in {@link #PROP}, which would pick the
     * start mode of every later connect, even of another monitor. Clears it if it's still there.
     */
    private void clearLeftoverStartMode() {
        try {
            if (!PROP_MARK.isFile()) return;
            String left = new String(java.nio.file.Files.readAllBytes(PROP_MARK.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).strip();
            if (!left.isEmpty() && left.equals(readStartMode())) {
                writeStartMode("");
                log.add("Cleared a monitor start mode (" + left + ") left by an earlier HzUnlock service.");
            }
            //noinspection ResultOfMethodCallIgnored
            PROP_MARK.delete();
        } catch (Exception e) {
            log.add("Couldn't check for a leftover monitor start mode: " + DisplayControl.describe(e));
        }
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
        // An undo or link recovery has stopped the tick and is turning the session off.
        if (worker != null && !active) return "High Refresh is turning off to restore DeX's standard modes. Try again in a moment.";
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
                if (rootLock != null) clearLeftoverStartMode();
                String existing = readStartMode();
                if (!existing.isEmpty()) log.add("The monitor start mode is already set to " + existing + " outside HzUnlock.");
            }
            rolledBack = null;
            modeResult = null;
            log.add("High refresh on. Monitor timing " + (noPacesetter ? "unavailable" : "available")
                    + " (calls " + DisplayControl.pacesetterCodes() + "), phone rates "
                    + (DisplayControl.canHoldPolicy() ? "available" : "unavailable") + ", " + (root ? "root" : "ADB shell") + ".");
            active = true;
            primed = false;
            // Arm before switching DeX's faster modes on: that reconnects the monitor, and a kept
            // start mode has to be set on that very connection.
            armKeptStart();
            if (keptStart != null && racer != null) log.add("Monitor start mode: " + keptStart.label() + " (kept earlier).");
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
        // Disarm first: switching DeX back to its standard modes reconnects the monitor, which
        // must come up in its own mode.
        if (racer != null) racer.disarm();
        switchStart = null;
        pendingStart = null;
        lastLead = null;
        String problem = null;
        try {
            setPacesetter(null);
        } catch (Exception e) {
            problem = "the timing source";
        }
        for (Long id : new HashSet<>(policies.keySet())) {
            if (!release(id)) problem = "a display's refresh policy";
        }
        boolean restoreRootMode = !reconnecting && needsStandardModeReconnect();
        if (modeTarget != null) {
            log.add("Monitor mode switch to " + modeTarget.label() + " cancelled: high refresh turned off.");
            if (modeResult == null) modeResult = "Switch to " + modeTarget.label() + " cancelled.";
        }
        modeTarget = null;
        modePhase = null;
        if (!clearStartMode("high refresh off")) problem = "the monitor's start mode";
        if (restoreDex && highResOn && DisplayControl.hasHighResolutionSwitch()) {
            try {
                if (restoreRootMode) {
                    try {
                        write(DP_DEX, DP_OFF);
                        write(DP_DEX, "17");
                    } finally {
                        try {
                            DisplayControl.setHighResolution(false);
                        } finally {
                            write(DP_DEX, DP_ON);
                        }
                    }
                } else {
                    DisplayControl.setHighResolution(false);
                }
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
        linkDrops.reset();
        phonePaced.clear();
        pacedRetried.clear();
        pacedMonitor = null;
        seenMonitors.clear();
        connectedSince.clear();
        autoReconnected.clear();
        limitedLinks.clear();
        reconnectedLinks.clear();
        keptModes.clear();
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
        // Not active while an undo is turning the session off; nothing may reconnect meanwhile.
        if (worker == null || !active) return;
        try {
            long now = SystemClock.elapsedRealtime();
            List<DisplayControl.Info> displays;
            try {
                displays = DisplayControl.displays();
            } catch (Exception e) {
                // A mode switch's time limits still apply while the displays can't be read.
                checkModeSwitch(null, now);
                throw e;
            }
            DisplayControl.Info phone = phone(displays);
            DisplayControl.Info lead = lead(displays);
            Set<Long> present = new HashSet<>();
            boolean newMonitor = false;
            // True when a new monitor may get DeX's faster modes switched on again.
            boolean unlockNew = false;
            for (DisplayControl.Info display : displays) {
                present.add(display.id());
                if (display.internal()) continue;
                String mode = describe(display.active());
                if (seenMonitors.add(display.id())) {
                    newMonitor = true;
                    if (!linkDrops.held(display.id()) && (!highResOn || display.active().hz() <= DEX_RATE)) unlockNew = true;
                    monitorNames.put(display.id(), display.name());
                    log.add("Monitor connected: " + display.name() + " " + mode + " (fastest "
                            + Math.round(display.fastest().hz()) + " Hz, " + display.modes().size() + " modes).");
                } else if (!mode.equals(loggedModes.get(display.id()))) {
                    log.add("Monitor mode changed: " + display.name() + " " + mode + ".");
                    connectedSince.put(display.id(), now);
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
            // DeX drops and re-adds the monitor on purpose during a reconnect, its settling time,
            // and a root mode switch (which has its own undo), so losses then don't count.
            // A monitor that goes away within seconds of HzUnlock moving app timing onto it may be
            // dropped by that change rather than by its link, so first keep its connection and
            // pace apps from the phone screen. A later drop counts as a link loss.
            Long dropped = pacedMonitor != null && !present.contains(pacedMonitor) ? pacedMonitor : null;
            if (dropped != null) pacedMonitor = null;
            boolean pacedDrop = dropped != null && now - pacedSince <= PACED_DROP_MS && !settling
                    && modeTarget == null && highResOn && phonePaced.add(dropped);
            if (pacedDrop) {
                linkDrops.pause();
                log.add("Android removed the monitor " + Math.max(0, (now - pacedSince) / 1000) + " s after app timing "
                        + "moved to it" + (pacedRetried.contains(dropped)
                        ? " again. The phone screen paces apps for this monitor from now on."
                        : ". The phone screen paces apps until the monitor stays connected for "
                        + PACED_RETRY_MS / 1000 + " s."));
            } else if (settling || modeTarget != null || !highResOn) {
                linkDrops.pause();
            } else {
                LinkDropTracker.Event event = linkDrops.observe(lead != null ? lead.id() : null,
                        lead != null ? lead.active().hz() : 0f, present, now, lastUnlock);
                if (event != LinkDropTracker.Event.NONE) forgetStartIfLost(linkDrops.lastLostHz());
                if (event == LinkDropTracker.Event.REPEATED) {
                    active = false;
                    rolledBack = "The monitor lost its high-refresh connection again within seconds of it starting. "
                            + "HzUnlock turned High Refresh off and requested DeX's standard modes.";
                    log.add(rolledBack);
                    new Thread(() -> stop(true), "hzunlock-link-recovery").start();
                    return;
                }
                if (event == LinkDropTracker.Event.LOST) {
                    log.add("Android removed the monitor within " + SHORT_LINK_MS / 1000 + " s of a high-refresh connection"
                            + " (" + Math.round(linkDrops.lastLostHz()) + " Hz). HzUnlock won't switch DeX's "
                            + "faster modes on again or reconnect automatically for this monitor; another such loss turns "
                            + "High Refresh off.");
                    rescue(now);
                }
            }
            if (lead != null) lastLead = lead;
            if (!settling) {
                connectedSince.keySet().retainAll(present);
                autoReconnected.retainAll(present);
                limitedLinks.keySet().retainAll(present);
                reconnectedLinks.retainAll(present);
                keptModes.keySet().retainAll(present);
            }
            if (newMonitor) {
                pacesetterStale = true;
                // A kept start mode set on this connection is checked once SurfaceFlinger and DeX have it.
                StartModeRacer.Attempt attempt = racer != null ? racer.last() : null;
                if (attempt != null && switchStart == null && keptStart != null && now - attempt.at() < 5_000
                        && (attempt.outcome() == StartModeRacer.Outcome.SET || attempt.outcome() == StartModeRacer.Outcome.ALREADY)) {
                    pendingStart = attempt;
                }
                // A newly attached monitor may need DeX's high-refresh modes unlocked again. A
                // reconnect sets the switch itself, and repeated calls are rate-limited. Skip a
                // working fast connection and leave a monitor held after a short link loss alone.
                if (unlockNew && !settling && (lastUnlock == 0 || now - lastUnlock > UNLOCK_GAP_MS)) {
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
                boolean phoneOnly = phonePaced.contains(lead.id());
                Long since = connectedSince.get(lead.id());
                if (phoneOnly && !pacedRetried.contains(lead.id()) && since != null && now - since >= PACED_RETRY_MS) {
                    // The first drop may have been the monitor's own; give it app timing once more.
                    phonePaced.remove(lead.id());
                    pacedRetried.add(lead.id());
                    phoneOnly = false;
                    log.add("The monitor stayed connected for " + PACED_RETRY_MS / 1000 + " s. App timing moves back to it.");
                }
                PhoneRate phoneRate = null;
                if (phone != null && DisplayControl.canHoldPolicy() && (noPacesetter || phoneOnly || want < link - 1f)) {
                    // Without pacesetter control the phone panel always paces, up to the monitor's rate.
                    float ceiling = noPacesetter ? link + 1f : link - 1f;
                    phoneRate = phoneRateFor(phone, want, ceiling, !noRenderCaps);
                }
                if (phoneRate != null && !hold(phone.id(), DisplayControl.token(phone.id()), phoneRate)) {
                    // SurfaceFlinger refused apps drawing below the panel's rate; the next tick
                    // picks from the panel's own modes.
                    phoneRate = null;
                    if (phone != null) release(phone.id());
                } else if (phoneRate != null) {
                    setPacesetter(phone.id());
                    pacedMonitor = null;
                    appHz = phoneRate.hz();
                    logRate(String.format(Locale.US, "Apps run at %d Hz, paced by the phone screen%s%s.",
                            Math.round(appHz), phoneRate.capped() ? " at " + Math.round(phoneRate.mode().hz()) + " Hz" : "",
                            throttled ? ", lowered for heat" : ""));
                } else if (phoneOnly) {
                    // No phone rate to hold: leave timing with Android rather than move it to the monitor.
                    if (phone != null) release(phone.id());
                    setPacesetter(null);
                    appHz = phone != null ? phone.active().hz() : 60f;
                    logRate("App timing stays with Android for this monitor.");
                } else if (link <= DEX_RATE) {
                    // A 60 Hz connection gains nothing from moving app timing to the monitor, so
                    // leave it with Android rather than force a timing source that can disappear.
                    if (phone != null) release(phone.id());
                    setPacesetter(null);
                    appHz = link;
                    logRate(String.format(Locale.US, "Monitor at %d Hz; app timing stays with Android.", Math.round(link)));
                } else {
                    if (phone != null) release(phone.id());
                    Long before = pacesetter;
                    setPacesetter(lead.id());
                    if (!noPacesetter && !Objects.equals(before, lead.id()) && Objects.equals(pacesetter, lead.id())) {
                        pacedMonitor = lead.id();
                        pacedSince = now;
                    }
                    appHz = noPacesetter ? (phone != null ? phone.active().hz() : 60f) : link;
                    logRate(String.format(Locale.US, "Apps run at %d Hz, paced by the %s.", Math.round(appHz),
                            noPacesetter ? "phone screen (no monitor timing)" : "monitor"));
                }
            }
            checkModeSwitch(lead, now);
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
     * The phone screen's fastest rate between 24 Hz and {@code ceiling} that doesn't exceed the
     * target, else its slowest one in that range. Rates that don't divide the monitor's rate
     * evenly are allowed: frames then stay up for an uneven number of monitor refreshes, like
     * 60 fps video on a 144 Hz monitor, which beats having no choice between 288 and 48 Hz.
     * Returns null when the panel has no rate in range.
     */
    static PhoneRate phoneRateFor(DisplayControl.Info phone, float target, float ceiling, boolean caps) {
        PhoneRate best = null;
        PhoneRate slowest = null;
        for (PhoneRate rate : phoneRates(phone, ceiling, caps)) {
            if (slowest == null || rate.hz() < slowest.hz()) slowest = rate;
            if (rate.hz() <= target + 0.5f && (best == null || rate.hz() > best.hz())) best = rate;
        }
        return best != null ? best : slowest;
    }

    /**
     * The rates the phone screen can pace apps at, one per whole-Hz rate between 24 Hz and
     * {@code ceiling}: its real modes at its current resolution and, with {@code caps}, whole
     * fractions of them. For each rate, the slowest panel mode that gives it is used, so a real
     * 60 Hz mode wins over 120 Hz drawing every other refresh.
     */
    static List<PhoneRate> phoneRates(DisplayControl.Info phone, float ceiling, boolean caps) {
        Map<Integer, PhoneRate> byHz = new HashMap<>();
        for (DisplayControl.Mode mode : phone.modes()) {
            if (mode.id() < 0) continue;
            if (mode.width() != phone.active().width() || mode.height() != phone.active().height()) continue;
            for (int divisor = 1; divisor == 1 || caps; divisor++) {
                float hz = mode.hz() / divisor;
                if (hz < LOWEST_RATE - 0.5f) break;
                // The ceiling limits the rate apps get; the panel itself may run faster than the monitor.
                // Fractions are offered only at whole rates (80 Hz gives 40, not 26.7).
                if (hz > ceiling || divisor > 1 && Math.abs(hz - Math.round(hz)) > 0.05f) continue;
                PhoneRate rate = new PhoneRate(mode, hz);
                byHz.merge(Math.round(hz), rate, (a, b) -> b.mode().hz() < a.mode().hz() ? b : a);
            }
        }
        return new ArrayList<>(byHz.values());
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

    /**
     * Pins a display's default mode and both ranges to {@code rate} so Android can't move it.
     * Returns false, and stops offering such rates, when SurfaceFlinger won't let apps draw below
     * the panel's own rate.
     */
    private boolean hold(long id, IBinder token, PhoneRate rate) throws Exception {
        if (token == null) return true;
        DisplayControl.Mode mode = rate.mode();
        Object current = DisplayControl.readSpecs(token);
        if (DisplayControl.isPinned(current, mode.id(), mode.hz(), rate.hz())) return true;
        Object applied;
        try {
            DisplayControl.writeSpecs(token, DisplayControl.pinnedSpecs(token, mode.id(), mode.hz(), rate.hz()));
            applied = DisplayControl.readSpecs(token);
            if (!DisplayControl.isPinned(applied, mode.id(), mode.hz(), rate.hz())) {
                throw new IOException("SurfaceFlinger didn't keep " + Math.round(rate.hz()) + " Hz.");
            }
        } catch (Exception e) {
            if (!rate.capped()) throw e;
            noRenderCaps = true;
            // Put back what was there, which is either Android's policy or one already recorded.
            try {
                DisplayControl.writeSpecs(token, current);
            } catch (Exception ignored) {
                // Releasing the phone policy below, or turning off, restores it.
            }
            log.add("SurfaceFlinger didn't keep apps at " + Math.round(rate.hz()) + " Hz on the phone screen's "
                    + Math.round(mode.hz()) + " Hz mode (" + DisplayControl.describe(e) + "). Offering only the "
                    + "phone screen's own modes from now on.");
            return false;
        }
        Policy previous = policies.get(id);
        // Keep Android's own policy for restoring; never record one of ours as the original.
        Object original = previous != null && previous.token().equals(token)
                && previous.applied().equals(DisplayControl.signature(current)) ? previous.original() : current;
        policies.put(id, new Policy(token, original, DisplayControl.signature(applied)));
        return true;
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
        // A mode the user chose with root is below the fastest on purpose; reconnecting would undo it.
        if (modeTarget != null || modeKey(display.active()).equals(keptModes.get(display.id()))) return false;
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
        reconnectedLinks.add(display.id());
        if (modeKey(display.active()).equals(keptModes.get(display.id()))) {
            // A chosen mode, not a limit of the connection.
            limitedLinks.remove(display.id());
            log.add("After reconnecting: " + describe(display.active()) + " (the chosen monitor mode).");
        } else if (display.belowFastest()) {
            limitedLinks.put(display.id(), display.active().hz());
            log.add("After reconnecting: still " + describe(display.active()) + " (fastest " + Math.round(display.fastest().hz())
                    + " Hz). The monitor starts in this mode; no more automatic reconnects for this plug-in.");
        } else {
            limitedLinks.remove(display.id());
            log.add("After reconnecting: " + describe(display.active()) + ".");
        }
    }

    /**
     * Fix Slow Connections. A reconnect starts the monitor in its default mode again; with root,
     * HzUnlock never picks a start mode on its own (the fastest listed mode may be one the hub or
     * monitor can't show), only the mode the user chose under Monitor Mode.
     */
    private void onSlowConnection(DisplayControl.Info display, long now) {
        // One automatic attempt per plug-in, only after the monitor has been up for a while,
        // never while a reconnect is settling, and never more than once per gap overall.
        if (!autoReconnect || checkReconnect || settling(now) || autoReconnected.contains(display.id())
                || linkDrops.held(display.id())) return;
        Long since = connectedSince.get(display.id());
        if (since == null || now - since < AUTO_RECONNECT_DELAY_MS) return;
        if (lastAutoReconnect != 0 && now - lastAutoReconnect < AUTO_RECONNECT_GAP_MS) return;
        autoReconnected.add(display.id());
        lastAutoReconnect = now;
        reconnect("automatic, monitor at " + Math.round(display.active().hz()) + " of "
                + Math.round(display.fastest().hz()) + " Hz", false);
    }

    // ---- reconnect -----------------------------------------------------------------------

    /** Reconnects the monitor because the user asked to. */
    synchronized String reconnect() {
        if (modeTarget != null) return "Finish the monitor mode switch first.";
        String result = reconnect("manual", false);
        // Asking for a reconnect is asking to try the faster modes again.
        if (result.startsWith("Reconnecting.")) linkDrops.reset();
        return result;
    }

    private synchronized String reconnect(String reason, boolean modeSwitch) {
        if (worker == null || !active) return "Turn on High Refresh first.";
        if (reconnecting) return "Already reconnecting.";
        if (!(root && new File(DP_DEX).exists()) && !DisplayControl.hasHighResolutionSwitch()) {
            log.add("Reconnect (" + reason + ") unavailable: no DeX high-refresh switch.");
            return "Unplug the monitor and plug it back in.";
        }
        log.add("Reconnecting the monitor (" + reason + ").");
        reconnecting = true;
        checkReconnect = false;
        reconnectFailed = false;
        // Move app timing off the monitor before DeX drops it, rather than after.
        try {
            setPacesetter(null);
        } catch (Exception e) {
            if (modeSwitch) {
                reconnecting = false;
                return "Couldn't release monitor timing. " + DisplayControl.describe(e);
            }
            log.add("Couldn't release app timing before reconnecting: " + DisplayControl.describe(e));
        }
        ModeTarget requested = modeSwitch ? modeTarget : null;
        Thread thread = new Thread(() -> {
            boolean switchedOff = false;
            try {
                if (requested != null && switchStart != null) {
                    reconnectForStart(requested);
                } else if (requested != null) {
                    reconnectForMode(requested);
                } else if (root && new File(DP_DEX).exists()) {
                    write(DP_DEX, "17");
                    SystemClock.sleep(6_000);
                    // If high refresh was turned off meanwhile, DeX stays in its standard modes.
                    if (active) write(DP_DEX, "0");
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
                reconnectFailed = true;
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
                if (!modeSwitch) SystemClock.sleep(4_000);
                reconnectEndedAt = SystemClock.elapsedRealtime();
                lastUnlock = reconnectEndedAt;
                // A mode switch's result is checked by checkModeSwitch, against the chosen mode.
                checkReconnect = !modeSwitch;
                reconnecting = false;
                log.add("Reconnect finished.");
            }
        }, "hzunlock-reconnect");
        thread.start();
        return "Reconnecting. The monitor goes dark for a few seconds.";
    }

    // ---- root monitor mode -----------------------------------------------------------------

    /**
     * Choosing the monitor's mode needs root (for {@link #PROP} and the driver reconnect), the
     * monitor's mode list from SurfaceFlinger, and DeX's high-refresh switch, which is the way
     * back to DeX's standard modes when a chosen mode doesn't work.
     */
    private boolean canSwitchModes() {
        return rootModes() || shellModes();
    }

    /** Root: the composer's start-mode property and a driver reconnect. */
    private boolean rootModes() {
        return root && DisplayControl.canReadModes() && DisplayControl.hasHighResolutionSwitch() && new File(DP_DEX).exists();
    }

    /** Without root (or without the root pieces): a start mode set through Qualcomm's display config service. */
    private boolean shellModes() {
        return !rootModes() && racer != null && DisplayControl.canReadModes() && DisplayControl.hasHighResolutionSwitch();
    }

    private boolean needsStandardModeReconnect() {
        if (!rootModes() || (modeTarget == null && keptModes.isEmpty())) return false;
        try {
            DisplayControl.Info current = lead(DisplayControl.displays());
            return current != null && current.active().hz() > DEX_RATE
                    && (modeTarget != null && modeTarget.display() == current.id() || keptModes.containsKey(current.id()));
        } catch (Exception ignored) {
            return false;
        }
    }

    /** The numeric DeX toggle can skip hotplug when the preferred and standard timings agree. */
    private void reconnectForMode(ModeTarget requested) throws Exception {
        if (!active) return;
        DisplayControl.Info current = lead(DisplayControl.displays());
        if (current == null || current.id() != requested.display()) throw new IOException("The monitor changed.");
        try {
            write(DP_DEX, DP_OFF);
            long deadline = SystemClock.elapsedRealtime() + MODE_DISCONNECT_MS;
            while (active && lead(DisplayControl.displays()) != null) {
                if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("The driver did not disconnect the monitor.");
                SystemClock.sleep(100);
            }
            synchronized (this) {
                if (!active || modeTarget != requested) return;
                if (!readStartMode().isEmpty()) throw new IOException("A monitor start mode is already set.");
                markStartMode(requested.key());
                writtenStartMode = requested.key();
                try {
                    writeStartMode(requested.key());
                } catch (Exception e) {
                    clearStartMode("the start-mode write failed");
                    throw e;
                }
            }
        } finally {
            write(DP_DEX, DP_ON);
        }
    }

    /** A mode's name in {@link #PROP}: the kernel's whole-number refresh, as {@code width:height:fps}. */
    static String modeKey(DisplayControl.Mode mode) {
        return mode.width() + ":" + mode.height() + ":" + Math.round(mode.hz());
    }

    /**
     * The modes the user can choose: each mode the monitor lists over this connection right now,
     * from 24 Hz up, except the current one. A name that fits two listed modes (59.94 and 60 Hz at
     * one size) is left out, because the composer would take whichever it finds first. The
     * current resolution comes first, then larger resolutions and faster rates first. Nothing
     * here is chosen automatically.
     */
    static List<DisplayControl.Mode> modeChoices(DisplayControl.Info display) {
        Map<String, List<DisplayControl.Mode>> byKey = new LinkedHashMap<>();
        for (DisplayControl.Mode mode : display.modes()) {
            if (!Float.isFinite(mode.hz()) || mode.hz() < LOWEST_RATE - 0.5f
                    || mode.width() <= 0 || mode.height() <= 0) continue;
            byKey.computeIfAbsent(modeKey(mode), key -> new ArrayList<>()).add(mode);
        }
        String activeKey = modeKey(display.active());
        List<DisplayControl.Mode> choices = new ArrayList<>();
        for (Map.Entry<String, List<DisplayControl.Mode>> entry : byKey.entrySet()) {
            if (entry.getValue().size() == 1 && !entry.getKey().equals(activeKey)) choices.add(entry.getValue().get(0));
        }
        DisplayControl.Mode active = display.active();
        choices.sort((a, b) -> {
            boolean aSame = a.width() == active.width() && a.height() == active.height();
            boolean bSame = b.width() == active.width() && b.height() == active.height();
            if (aSame != bSame) return aSame ? -1 : 1;
            int bySize = Long.compare((long) b.width() * b.height(), (long) a.width() * a.height());
            if (bySize != 0) return bySize;
            if (a.width() != b.width()) return Integer.compare(b.width(), a.width());
            return Integer.compare(Math.round(b.hz()), Math.round(a.hz()));
        });
        return choices;
    }

    /**
     * False for modes no one would pick in DeX on {@code display}: tiny resolutions, rates no
     * faster than DeX's own 60 Hz, and smaller sizes that aren't faster than the current size's
     * fastest mode (a 240 Hz monitor's 1280 × 1024 at 75 Hz, say).
     */
    static boolean worthChoosing(DisplayControl.Info display, DisplayControl.Mode mode) {
        long pixels = (long) mode.width() * mode.height();
        if (pixels < MIN_CHOICE_PIXELS || mode.hz() <= DEX_RATE) return false;
        DisplayControl.Mode active = display.active();
        return pixels >= (long) active.width() * active.height() || mode.hz() > display.fastest().hz() + 1f;
    }

    /** "144 Hz" when the size stays as it is now, else "2560 × 1440, 120 Hz". */
    static String shortLabel(ModeTarget target, DisplayControl.Mode now) {
        if (now != null && now.width() == target.width() && now.height() == target.height()) return target.hz() + " Hz";
        return target.width() + " × " + target.height() + ", " + target.hz() + " Hz";
    }

    /** A mode HzUnlock recommends: "slow" (started at 60 Hz, lists faster) or "fast" (near the link's limit). */
    record Suggestion(DisplayControl.Mode mode, String reason) {}

    /**
     * Adds the mode HzUnlock recommends for this connection, if any, as {@code suggest}, once DeX
     * has settled after its reconnects.
     */
    private void suggest(DisplayControl.Info lead, JSONObject out) throws JSONException {
        long now = SystemClock.elapsedRealtime();
        if (reconnecting || settling(now) || now - lastUnlock < SUGGEST_SETTLE_MS) return;
        Long since = connectedSince.get(lead.id());
        if (since == null || now - since < SUGGEST_SETTLE_MS) return;
        String monitorKey = fastestKey(lead);
        Suggestion pick = suggestion(lead, mode -> failedModes.contains(monitorKey + "|" + modeKey(mode)));
        if (pick == null) return;
        DisplayControl.Mode best = pick.mode();
        ModeTarget target = new ModeTarget(lead.id(), best.width(), best.height(), Math.round(best.hz()));
        JSONObject suggestion = new JSONObject();
        suggestion.put("width", target.width());
        suggestion.put("height", target.height());
        suggestion.put("hz", target.hz());
        suggestion.put("label", shortLabel(target, lead.active()));
        suggestion.put("reason", pick.reason());
        out.put("suggest", suggestion);
    }

    /**
     * The one mode to recommend for {@code lead}, else null:
     * <ul>
     *   <li>"slow": the monitor started at DeX's 60 Hz but lists faster modes. The fastest at its
     *       current size, else the largest faster mode of at least 1080 lines, as on a TV at 4K 60
     *       that lists 1440p or 1080p at 120.</li>
     *   <li>"fast": a connection at {@link #NEAR_LIMIT_HZ} or more, which uses nearly all of the
     *       phone's link (1080p 288): half the rate at the same size.</li>
     * </ul>
     * Modes for which {@code failed} is true (undone or dropped before) are never recommended.
     */
    static Suggestion suggestion(DisplayControl.Info lead, java.util.function.Predicate<DisplayControl.Mode> failed) {
        DisplayControl.Mode active = lead.active();
        DisplayControl.Mode best = null;
        if (active.hz() <= DEX_RATE) {
            for (DisplayControl.Mode mode : modeChoices(lead)) {
                boolean sameSize = mode.width() == active.width() && mode.height() == active.height();
                if (!worthChoosing(lead, mode) || mode.hz() <= active.hz() + 1f || !sameSize && mode.height() < 1080
                        || failed.test(mode)) continue;
                if (best == null) {
                    best = mode;
                    continue;
                }
                boolean bestSame = best.width() == active.width() && best.height() == active.height();
                long pixels = (long) mode.width() * mode.height();
                long bestPixels = (long) best.width() * best.height();
                if (sameSize != bestSame ? sameSize
                        : pixels != bestPixels ? pixels > bestPixels : mode.hz() > best.hz()) {
                    best = mode;
                }
            }
            return best == null ? null : new Suggestion(best, "slow");
        }
        if (active.hz() < NEAR_LIMIT_HZ) return null;
        int want = Math.round(active.hz() / 2f);
        for (DisplayControl.Mode mode : modeChoices(lead)) {
            if (mode.width() == active.width() && mode.height() == active.height()
                    && Math.abs(Math.round(mode.hz()) - want) <= 1 && !failed.test(mode)) {
                return new Suggestion(mode, "fast");
            }
        }
        return null;
    }

    /**
     * Restarts the monitor in a mode the user chose, with root: writes the exact mode to
     * {@link #PROP} and reconnects the DisplayPort driver. The choice is checked again here
     * against the monitor connected right now; anything that isn't one of its current choices is
     * refused, never replaced by another mode. Once the monitor is back, the property is cleared
     * and the user has {@link #MODE_CONFIRM_MS} to keep the mode. If they don't, if the reconnect
     * fails, or if the monitor doesn't come back, HzUnlock turns high refresh off, which puts DeX
     * back in its standard modes. That's one more reconnect at most, never a retry.
     */
    synchronized String switchMode(long displayId, int width, int height, int hz) {
        if (worker == null || !active) return "Turn on High Refresh first.";
        if (!canSwitchModes()) {
            return "Choosing the monitor's mode needs a Snapdragon phone (or root) and Samsung's DeX mode switch.";
        }
        boolean shell = !rootModes();
        if (!shell && rootLock == null) return "Couldn't lock the root display controls.";
        if (!highResOn) {
            return "DeX's high-refresh modes didn't switch on, so a switch couldn't be undone. Turn High Refresh off and on again.";
        }
        if (reconnecting || modeTarget != null) return "Wait for the current reconnect to finish.";
        DisplayControl.Info lead;
        try {
            lead = lead(DisplayControl.displays());
        } catch (Exception e) {
            return "Couldn't read the monitor's modes. " + DisplayControl.describe(e);
        }
        if (lead == null || lead.id() != displayId) return "The monitor changed. Choose its mode again.";
        boolean offered = false;
        for (DisplayControl.Mode mode : modeChoices(lead)) {
            if (mode.width() == width && mode.height() == height && Math.round(mode.hz()) == hz) offered = true;
        }
        ModeTarget target = new ModeTarget(lead.id(), width, height, hz);
        if (!offered) {
            log.add("Monitor mode " + target.key() + " refused: not one of the monitor's current choices.");
            return "The monitor doesn't offer " + target.label() + " right now. Choose again.";
        }
        modeShortLabel = shortLabel(target, lead.active());
        modeMonitorKey = fastestKey(lead);
        if (shell) {
            modeTarget = target;
            modePhase = ModePhase.SWITCHING;
            modeResult = null;
            log.add("Starting the monitor at " + target.label() + " instead of " + describe(lead.active()) + " (chosen by the user).");
            beginStartSwitch(lead, target, false);
            return "Switching to " + modeShortLabel + ".";
        }
        try {
            if (!clearStartMode("before another switch") || !readStartMode().isEmpty()) {
                return "Another monitor start mode is already set.";
            }
        } catch (Exception e) {
            return "Couldn't read the monitor's start mode. " + DisplayControl.describe(e);
        }
        modeTarget = target;
        modePhase = ModePhase.SWITCHING;
        modeResult = null;
        log.add("Switching the monitor from " + describe(lead.active()) + " to " + target.label() + " (chosen by the user).");
        String result = reconnect("monitor mode " + target.key(), true);
        if (!reconnecting) {
            modeTarget = null;
            modePhase = null;
            clearStartMode("reconnect didn't start");
            return result;
        }
        return "Switching to " + modeShortLabel + ".";
    }

    /** The user's answer to "keep this mode?". Undo turns high refresh off right away. */
    synchronized String confirmMode(boolean keep) {
        if (!active) return "The switch is being undone.";
        ModeTarget target = modeTarget;
        if (target == null) return "There's no monitor mode switch to confirm.";
        if (!keep && switchStart != null && modePhase != ModePhase.CONFIRM && racer.matches() == switchBaseline) {
            // Nothing has been switched yet: the monitor is still in its own mode.
            cancelStartSwitch("Cancelled. Nothing changed.");
            return "Cancelled.";
        }
        if (!keep) {
            rollBack("undone");
            return "Undoing the switch. High Refresh turns off and DeX goes back to its standard modes.";
        }
        if (modePhase != ModePhase.CONFIRM) return "Wait until the monitor is back.";
        try {
            DisplayControl.Info lead = lead(DisplayControl.displays());
            if (lead == null || lead.id() != target.display() || !modeKey(lead.active()).equals(target.key())) {
                return "The monitor changed. This switch will be undone.";
            }
            keptModes.put(lead.id(), modeKey(lead.active()));
            StartModeRacer.Target start = switchStart;
            if (start != null) {
                keptStart = start;
                saveStart(start);
                switchStart = null;
                racer.arm(start, 0);
                String name = modeShortLabel != null ? modeShortLabel : start.label();
                modeResult = "Kept. The monitor starts at " + name + " each time.";
            } else {
                modeResult = "Kept until the monitor reconnects.";
            }
            log.add("Kept the monitor mode " + describe(lead.active()) + ".");
        } catch (Exception e) {
            return "Couldn't read the monitor. " + DisplayControl.describe(e);
        }
        modeTarget = null;
        modePhase = null;
        return "Kept.";
    }

    private long lastClearAttempt;

    /** Moves a mode switch along, on the session's tick. {@code lead} is null when no monitor (or none readable). */
    private void checkModeSwitch(DisplayControl.Info lead, long now) {
        ModeTarget target = modeTarget;
        if (target == null) {
            // A start mode whose clear failed must not stay for the next connect. Retry now and then.
            if (writtenStartMode != null && now - lastClearAttempt > VERIFY_MS) {
                lastClearAttempt = now;
                clearStartMode("retry");
            }
            checkKeptStart(lead, now);
            return;
        }
        if (switchStart != null) {
            checkStartSwitch(target, lead, now);
            return;
        }
        if (reconnecting) return;
        if (lead != null && lead.id() != target.display()) {
            clearStartMode("a different monitor attached");
            modeTarget = null;
            modePhase = null;
            modeResult = "A different monitor connected. Switch cancelled.";
            log.add(modeResult);
            return;
        }
        if (modePhase == ModePhase.SWITCHING) {
            if (reconnectFailed) {
                rollBack("the reconnect failed");
                return;
            }
            long ended = reconnectEndedAt;
            if (lead == null) {
                if (now - ended >= MODE_RETURN_MS) {
                    rollBack("the monitor didn't come back");
                }
                return;
            }
            // The start mode is read only on connect, and this connect is done. Clear it so no later
            // connect, of this monitor or another, picks it up.
            if (!clearStartMode("switch checked")) {
                rollBack("the start mode could not be cleared");
                return;
            }
            DisplayControl.Mode came = lead.active();
            String arrived = new ModeTarget(lead.id(), came.width(), came.height(), Math.round(came.hz())).label();
            boolean matched = modeKey(came).equals(target.key());
            if (!matched) {
                rollBack("the monitor came back at " + arrived);
                return;
            }
            log.add("The monitor came back at " + arrived + ". Waiting for the user to keep it.");
            modePhase = ModePhase.CONFIRM;
            modeDeadline = now + MODE_CONFIRM_MS;
        } else if (lead == null) {
            rollBack("the monitor disconnected");
        } else if (now >= modeDeadline) {
            rollBack("not kept in time");
        }
    }

    /**
     * Undoes a mode switch: clears the start mode and turns high refresh off, which switches DeX
     * back to its standard modes and reconnects the monitor once. Runs the stop on its own thread,
     * since the session's tick may be the caller.
     */
    private synchronized void rollBack(String reason) {
        ModeTarget target = modeTarget;
        if (target == null) return;
        // Stops the tick and keeps a running reconnect from switching DeX's faster modes back on.
        if (!active) return;
        active = false;
        clearStartMode("switch undone");
        String monitor = modeMonitorKey;
        if (monitor != null) failedModes.add(monitor + "|" + target.key());
        String name = modeShortLabel != null ? modeShortLabel : target.label();
        rolledBack = "High Refresh turned off to undo " + name + " (" + reason + "). DeX is back in its standard modes.";
        modeResult = rolledBack;
        log.add("Undoing the monitor mode switch (" + reason + "): turning high refresh off.");
        new Thread(() -> stop(true), "hzunlock-undo").start();
    }

    /** Clears {@link #PROP} if it still holds HzUnlock's value. Returns false when that failed. */
    private boolean clearStartMode(String why) {
        String written = writtenStartMode;
        if (written == null) return true;
        try {
            if (written.equals(readStartMode())) {
                writeStartMode("");
                log.add("Cleared the monitor start mode " + written + " (" + why + ").");
            }
            writtenStartMode = null;
            //noinspection ResultOfMethodCallIgnored
            PROP_MARK.delete();
            return true;
        } catch (Exception e) {
            log.add("Couldn't clear the monitor start mode " + written + " (" + why + "): " + DisplayControl.describe(e));
            return false;
        }
    }

    private static void markStartMode(String value) throws IOException {
        java.nio.file.Files.write(PROP_MARK.toPath(), value.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    // ---- start mode without root ----------------------------------------------------------

    /** Arms the racer for the kept start mode, if there is one and HzUnlock sets it without root. */
    private void armKeptStart() {
        StartModeRacer.Target start = keptStart;
        if (racer != null && start != null && shellModes()) racer.arm(start, StartModeRacer.AFTER_DISCONNECT_MS);
    }

    /**
     * The monitor's fastest mode by rate (larger first on a tie), named like
     * {@link DisplayConfig#fastestKey}. It tells monitors, and one monitor's presets, apart.
     */
    static String fastestKey(DisplayControl.Info display) {
        DisplayControl.Mode best = null;
        for (DisplayControl.Mode mode : display.modes()) {
            int hz = Math.round(mode.hz());
            int bestHz = best == null ? -1 : Math.round(best.hz());
            if (best == null || hz > bestHz
                    || hz == bestHz && (long) mode.width() * mode.height() > (long) best.width() * best.height()) {
                best = mode;
            }
        }
        return best == null ? "" : best.width() + "x" + best.height() + "@" + Math.round(best.hz());
    }

    /** Starts a switch into {@code target} through the racer. The tick does the rest (see {@link #checkStartSwitch}). */
    private void beginStartSwitch(DisplayControl.Info monitor, ModeTarget target, boolean rescue) {
        StartModeRacer.Target start = new StartModeRacer.Target(target.width(), target.height(), target.hz(), fastestKey(monitor));
        switchStart = start;
        switchBaseline = racer.matches();
        switchStartedAt = SystemClock.elapsedRealtime();
        switchToggled = false;
        switchTries = 0;
        switchRescue = rescue;
        switchRejoinUntil = 0;
        racer.arm(start, REPLUG_MS);
    }

    /**
     * The reconnect of a start-mode switch: DeX's standard modes for 5 s, then its faster modes
     * again, which reconnects the monitor in its default mode, where the racer switches it. Then
     * waits a few seconds for the composer to report that connection.
     */
    private void reconnectForStart(ModeTarget requested) throws Exception {
        if (!active) return;
        int baseline = racer.matches();
        boolean off = false;
        try {
            DisplayControl.setHighResolution(false);
            off = true;
            SystemClock.sleep(5_000);
        } finally {
            // Never leave DeX's faster modes off while high refresh is on.
            if (off && active) {
                DisplayControl.setHighResolution(true);
                highResOn = true;
                lastUnlock = SystemClock.elapsedRealtime();
            }
        }
        long until = SystemClock.elapsedRealtime() + START_MATCH_MS;
        while (active && modeTarget == requested && racer.matches() == baseline && SystemClock.elapsedRealtime() < until) {
            SystemClock.sleep(50);
        }
    }

    /**
     * Moves a start-mode switch along. A connection the racer switched is judged once
     * SurfaceFlinger has it: in the target mode it waits to be kept; otherwise SurfaceFlinger read
     * the monitor first, and the switch is tried once more before High Refresh turns off. Without
     * such a connection, it reconnects the monitor through DeX, and if that doesn't reconnect it,
     * asks the user to replug it.
     */
    private void checkStartSwitch(ModeTarget target, DisplayControl.Info lead, long now) {
        if (modePhase == ModePhase.CONFIRM) {
            if (lead == null) {
                // Usually DeX re-adding the monitor; wait for the next connection and judge it again.
                modePhase = ModePhase.SWITCHING;
                switchBaseline = racer.matches();
                switchToggled = true;
                switchRejoinUntil = now + MODE_RETURN_MS;
                log.add("The monitor disconnected while waiting to be kept. Waiting up to " + MODE_RETURN_MS / 1000
                        + " s for it to come back in " + target.label() + ".");
            }
            else if (!modeKey(lead.active()).equals(target.key())) rollBack("the monitor changed to " + describe(lead.active()));
            else if (now >= modeDeadline) rollBack("not kept in time");
            return;
        }
        if (reconnecting) return;
        if (switchToggled && reconnectFailed) {
            rollBack("the reconnect failed");
            return;
        }
        StartModeRacer.Attempt attempt = racer.last();
        int matched = racer.matches();
        if (matched > switchBaseline && attempt != null) {
            if (now - attempt.at() < START_JUDGE_MS) return;
            if (lead == null) {
                if (now - attempt.at() >= MODE_RETURN_MS) rollBack("the monitor didn't come back");
                return;
            }
            switchRejoinUntil = 0;
            if (lead.id() != target.display()) {
                cancelStartSwitch("A different monitor connected. Switch cancelled.");
                return;
            }
            String took = String.format(Locale.US, "%.1f ms", attempt.micros() / 1000f);
            if (modeKey(lead.active()).equals(target.key())) {
                log.add("The monitor started at " + target.label() + " (set " + took + " after the composer saw it). "
                        + "Waiting for the user to keep it.");
                modePhase = ModePhase.CONFIRM;
                modeDeadline = now + MODE_CONFIRM_MS;
                // A rescue starts without the user: bring up HzUnlock so the Keep prompt can be seen.
                if (switchRescue) showApp();
                return;
            }
            log.add("SurfaceFlinger read the monitor's own mode (" + describe(lead.active()) + ") before " + target.label()
                    + " was set, " + took + " after the connection.");
            if (++switchTries < 2) {
                // Reconnect and race once more, right away.
                switchBaseline = matched;
                switchToggled = false;
                switchStartedAt = now - RESCUE_WAIT_MS;
                return;
            }
            rollBack("the phone read the monitor too early, twice");
            return;
        }
        if (modePhase == ModePhase.REPLUG) {
            if (now >= modeDeadline) {
                cancelStartSwitch("The monitor wasn't plugged back in. Nothing changed.");
            }
            return;
        }
        if (!switchToggled) {
            // A rescue first waits for DeX to re-add the monitor by itself.
            if (switchRescue && now - switchStartedAt < RESCUE_WAIT_MS) return;
            switchToggled = true;
            String result = reconnect("monitor start mode " + target.key(), true);
            if (!reconnecting) {
                log.add("Couldn't reconnect the monitor for its start mode: " + result);
                askReplug(now);
            }
            return;
        }
        if (switchRejoinUntil != 0) {
            if (now >= switchRejoinUntil) rollBack("the monitor didn't come back");
            return;
        }
        // DeX's reconnect ended without a connection the racer could switch (it often doesn't
        // reconnect a monitor whose default mode is already one of DeX's standard modes).
        askReplug(now);
    }

    /** Opens HzUnlock (on the DeX screen in DeX), as the shell user may. */
    private void showApp() {
        new Thread(() -> {
            try {
                DisplayControl.run(5, "/system/bin/am", "start", "-n", BuildConfig.APPLICATION_ID + "/.MainActivity");
            } catch (Exception e) {
                log.add("Couldn't open HzUnlock for the Keep prompt: " + DisplayControl.describe(e));
            }
        }, "hzunlock-show").start();
    }

    private void askReplug(long now) {
        modePhase = ModePhase.REPLUG;
        modeDeadline = now + REPLUG_MS;
        racer.arm(switchStart, REPLUG_MS);
        log.add("The monitor didn't reconnect. Waiting up to " + REPLUG_MS / 1000 + " s for it to be unplugged and plugged back in.");
    }

    /** Ends a start-mode switch that hasn't changed anything; the kept start mode, if any, stays armed. */
    private void cancelStartSwitch(String message) {
        modeTarget = null;
        modePhase = null;
        switchStart = null;
        StartModeRacer.Target start = keptStart;
        if (start != null && shellModes()) racer.arm(start, 0);
        else racer.disarm();
        modeResult = message;
        log.add(message);
    }

    /**
     * After a kept start mode was set on a new connection: confirms SurfaceFlinger took it. When
     * SurfaceFlinger read the monitor's own mode first, app timing would follow that mode, so the
     * monitor is put back in it, unless the start mode divides it evenly (288 → 144), which keeps
     * frames on whole refreshes and the picture the start mode is there to save.
     */
    private void checkKeptStart(DisplayControl.Info lead, long now) {
        StartModeRacer.Attempt attempt = pendingStart;
        if (attempt == null || now - attempt.at() < START_JUDGE_MS) return;
        pendingStart = null;
        if (lead == null) return;
        StartModeRacer.Target start = attempt.target();
        String took = String.format(Locale.US, "%.1f ms", attempt.micros() / 1000f);
        DisplayControl.Mode read = lead.active();
        if (modeKey(read).equals(start.key())) {
            keptModes.put(lead.id(), start.key());
            log.add("Started the monitor at " + start.label() + " (start mode, set " + took + " after the composer saw it).");
            return;
        }
        if (read.width() == start.width() && read.height() == start.height() && smooth(read.hz(), start.hz())) {
            log.add("SurfaceFlinger read " + describe(read) + " before the start mode landed (" + took + "). The monitor runs at "
                    + start.label() + ", which divides it evenly, so HzUnlock keeps it. Replug the monitor to start it cleanly.");
            return;
        }
        try {
            int index = composerIndex(read, attempt.previousIndex());
            if (index >= 0) displayConfig.set(index);
            log.add("SurfaceFlinger read " + describe(read) + " before the start mode landed (" + took + "). Put the monitor "
                    + "back in that mode so app timing matches. Replug it to try again.");
        } catch (Exception e) {
            log.add("Couldn't put the monitor back at " + describe(read) + ": " + DisplayControl.describe(e));
        }
    }

    /** The composer's index for {@code mode}, trying {@code preferred} first; -1 when it isn't listed. */
    private int composerIndex(DisplayControl.Mode mode, int preferred) throws android.os.RemoteException {
        int hz = Math.round(mode.hz());
        DisplayConfig.Config at = preferred >= 0 ? displayConfig.config(preferred) : null;
        if (at != null && at.is(mode.width(), mode.height(), hz)) return preferred;
        for (DisplayConfig.Config config : displayConfig.configs()) {
            if (config.is(mode.width(), mode.height(), hz)) return config.index();
        }
        return -1;
    }

    /**
     * After Android removed a monitor within seconds of a connection at {@link #RESCUE_MIN_HZ} or
     * more (a mode the phone or the link can't hold), prepares half that rate as its start mode, so
     * the next connection, which DeX usually makes by itself, comes up there. The user still has to
     * keep it; otherwise High Refresh turns off as before.
     */
    private void rescue(long now) {
        DisplayControl.Info monitor = lastLead;
        float lost = linkDrops.lastLostHz();
        if (!active || !shellModes() || modeTarget != null || monitor == null || lost < RESCUE_MIN_HZ) return;
        StartModeRacer.Target start = keptStart;
        if (start != null && start.fastestKey().equals(fastestKey(monitor))) return;
        int want = Math.round(lost / 2f);
        DisplayControl.Mode half = null;
        for (DisplayControl.Mode mode : modeChoices(monitor)) {
            if (mode.width() == monitor.active().width() && mode.height() == monitor.active().height()
                    && Math.abs(Math.round(mode.hz()) - want) <= 1) {
                half = mode;
                break;
            }
        }
        if (half == null) return;
        ModeTarget target = new ModeTarget(monitor.id(), half.width(), half.height(), Math.round(half.hz()));
        modeTarget = target;
        modePhase = ModePhase.SWITCHING;
        modeResult = null;
        modeShortLabel = shortLabel(target, monitor.active());
        modeMonitorKey = fastestKey(monitor);
        log.add("The monitor dropped at " + Math.round(lost) + " Hz. HzUnlock will start it at " + target.label()
                + " on its next connection; keep that mode within " + MODE_CONFIRM_MS / 1000 + " s once it shows a picture.");
        beginStartSwitch(monitor, target, true);
    }

    /** A kept start mode the monitor just dropped at isn't kept any longer. */
    private void forgetStartIfLost(float lostHz) {
        StartModeRacer.Target start = keptStart;
        if (start == null || Math.abs(start.hz() - lostHz) > 1f) return;
        log.add("The monitor dropped within seconds at its start mode " + start.label() + ". HzUnlock forgot that start mode.");
        failedModes.add(start.fastestKey() + "|" + start.key());
        forgetStart();
    }

    private void forgetStart() {
        keptStart = null;
        //noinspection ResultOfMethodCallIgnored
        START_FILE.delete();
        if (racer != null && switchStart == null) racer.disarm();
    }

    /** The user's "Default" under Monitor Mode: stops setting a start mode. */
    synchronized String forgetStartMode() {
        StartModeRacer.Target start = keptStart;
        if (start == null) return "HzUnlock isn't setting a start mode.";
        if (modeTarget != null) return "Finish the monitor mode switch first.";
        forgetStart();
        // The monitor stays in that mode until its next connection, which then isn't a chosen one.
        keptModes.values().removeIf(start.key()::equals);
        log.add("Forgot the monitor start mode " + start.label() + " (chosen by the user).");
        modeResult = "Back to the monitor's own mode from its next connection.";
        return "Back to the monitor's own mode from its next connection.";
    }

    private static StartModeRacer.Target loadStart() {
        try {
            if (!START_FILE.isFile()) return null;
            return StartModeRacer.Target.parse(new String(java.nio.file.Files.readAllBytes(START_FILE.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private void saveStart(StartModeRacer.Target start) {
        try {
            java.nio.file.Files.write(START_FILE.toPath(), start.serialize().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.add("Couldn't save the start mode; it lasts until High Refresh turns off: " + DisplayControl.describe(e));
        }
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
        boolean modeControl = canSwitchModes();
        caps.put("monitorModes", modeControl);
        // "root": the composer's start-mode property; "start": set at connect without root.
        caps.put("modeMethod", rootModes() ? "root" : shellModes() ? "start" : "none");
        caps.put("displayConfig", racer != null);
        json.put("caps", caps);

        ModeTarget target = modeTarget;
        if (target != null) {
            JSONObject switching = new JSONObject();
            switching.put("label", target.label());
            String shortName = modeShortLabel;
            switching.put("short", shortName != null ? shortName : target.label());
            if (switchRescue && switchStart != null) switching.put("lostHz", Math.round(linkDrops.lastLostHz()));
            boolean confirm = modePhase == ModePhase.CONFIRM;
            switching.put("confirm", confirm);
            boolean replug = modePhase == ModePhase.REPLUG;
            if (replug) switching.put("replug", true);
            if (switchRescue && switchStart != null) switching.put("rescue", true);
            if (confirm || replug) {
                long left = modeDeadline - SystemClock.elapsedRealtime();
                switching.put("secondsLeft", Math.max(0, (left + 999) / 1000));
            }
            json.put("modeSwitch", switching);
        }
        if (modeResult != null) json.put("modeResult", modeResult);
        if (!running && rolledBack != null) json.put("rolledBack", rolledBack);

        try {
            List<DisplayControl.Info> displays = DisplayControl.displays();
            DisplayControl.Info phone = phone(displays);
            DisplayControl.Info lead = lead(displays);
            if (lead != null) {
                if (running && !describe(lead.active()).equals(loggedModes.get(lead.id()))) json.put("waiting", true);
                float link = lead.active().hz();
                TreeSet<Integer> stops = new TreeSet<>();
                boolean phoneOnly = running && phonePaced.contains(lead.id());
                if (!noPacesetter && !phoneOnly) stops.add(Math.round(link));
                if (phone != null && DisplayControl.canHoldPolicy()) {
                    for (PhoneRate rate : phoneRates(phone, noPacesetter ? link + 1f : link - 1f, !noRenderCaps)) {
                        stops.add(Math.round(rate.hz()));
                    }
                }
                if (stops.isEmpty()) stops.add(Math.round(noPacesetter && phone != null ? phone.fastest().hz() : link));
                // Only rates the current connection really runs at. The slider paces apps; it never
                // changes the monitor's own mode, which only root can choose (Monitor Mode).
                json.put("stops", new JSONArray(stops));

                JSONObject monitor = new JSONObject();
                monitor.put("name", lead.name());
                monitor.put("width", lead.active().width());
                monitor.put("height", lead.active().height());
                monitor.put("linkHz", link);
                monitor.put("fastestHz", lead.fastest().hz());
                // A listed timing reached Android's mode list. The monitor started in another mode.
                monitor.put("fasterListed", lead.belowFastest());
                if (checkReconnect || reconnectedLinks.contains(lead.id())) monitor.put("reconnected", true);
                Float limited = limitedLinks.get(lead.id());
                if (limited != null && lead.belowFastest()) monitor.put("limitedHz", limited);
                Float lost = linkDrops.lostHz(lead.id());
                if (running && lost != null) monitor.put("lostHz", lost);
                if (phoneOnly) {
                    monitor.put("phonePaced", true);
                    if (!pacedRetried.contains(lead.id())) monitor.put("phonePacedRetry", true);
                }
                describeModes(lead, monitor);
                if (modeControl) {
                    monitor.put("id", lead.id());
                    monitor.put("modeKey", modeKey(lead.active()));
                    JSONArray choices = new JSONArray();
                    for (DisplayControl.Mode mode : modeChoices(lead)) {
                        if (!worthChoosing(lead, mode)) continue;
                        JSONObject choice = new JSONObject();
                        choice.put("width", mode.width());
                        choice.put("height", mode.height());
                        choice.put("hz", Math.round(mode.hz()));
                        choices.put(choice);
                    }
                    monitor.put("modeChoices", choices);
                    // Only while the monitor is in it: a reconnect after Use Default leaves another mode.
                    String kept = keptModes.get(lead.id());
                    if (kept != null && !kept.equals(modeKey(lead.active()))) kept = null;
                    if (kept != null) monitor.put("keptMode", kept);
                    StartModeRacer.Target start = keptStart;
                    boolean startKept = start != null && racer != null && start.fastestKey().equals(fastestKey(lead));
                    if (startKept) {
                        monitor.put("startMode", start.label());
                        monitor.put("startModeKey", start.key());
                    }
                    if (running && target == null && kept == null && !startKept) suggest(lead, monitor);
                }
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
