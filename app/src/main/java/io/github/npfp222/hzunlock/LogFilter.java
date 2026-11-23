package io.github.npfp222.hzunlock;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the system log lines that matter for display problems (DeX, SurfaceFlinger, display
 * and DisplayPort drivers, thermal, crashes, HzUnlock itself) out of a {@code logcat -v threadtime}
 * dump, and masks e-mail addresses and long tokens so a shared report carries as little personal
 * data as possible. Plain Java so it can be tested off the device.
 */
final class LogFilter {
    /** Tag fragments (lower case) that mark a relevant line. */
    private static final String[] TAGS = {
            "desktop", "dex", "display", "surfaceflinger", "hwc", "hwcomposer", "hzunlock", "androidruntime",
            "thermal", "secdp", "displayport", "hdmi", "shizuku", "multitasking",
    };
    /** Native crash tags and Qualcomm's composer (SDM, which logs hotplugs and mode changes), matched exactly. */
    private static final String[] EXACT = {"debug", "libc", "crash_dump64", "tombstoned", "sdm"};
    /**
     * Tags that contain a fragment above but are unrelated noise: the DeX launcher and taskbar
     * (HoneySpace logs hundreds of layout lines on each DeX change) and per-window view logs.
     */
    private static final String[] IGNORED = {"dex2oat", "dexopt", "dexoptimizer", "dexmanager_art", "honeyspace", "vri["};
    /**
     * Message starts that are noise whatever the tag: auto-brightness logs a pair of these every
     * few milliseconds and would push every useful line out of the report.
     */
    private static final String[] NOISE = {"setDisplayBrightness", "surface lcd :", "composite:Output layer",
            "RequestedLayer [", "destroyed ", "[LayerHierarchy{", "GPIS::",
            // Desktop windowing bookkeeping, logged for every task on every DeX change.
            "DesktopTasksController: Display 0 does not support desktop mode", "DesktopRepository(",
            "RootTaskDesksOrganizer: onTaskInfoChanged",
            // The thermal service logs each read, and HzUnlock reads it every 2 s for Cool Down.
            "getTemperatures", "getTemperatureThresholds",
            // SurfaceFlinger, per window: its first frame, fade animations, and a periodic timer.
            "alpha changed ", "SFWD update time"};
    /**
     * Message fragments that are noise whatever the tag: SurfaceFlinger logs every window layer
     * it creates, re-parents and removes (each toast or tap makes several).
     */
    private static final String[] NOISE_ANYWHERE = {" createSurf, ", "] attach to parent ", "] reparent to ",
            "] RelativeParent to ", " onLayerDestroyed ", " Removed VRI-", " Removed WindowToken", " Destroyed VRI-",
            " Destroyed WindowToken", "Rendering layer ", "::SetPanelBrightness", "] setTransactionState with the first frame"};
    /**
     * Tags that repeat the same line for every frame-rate vote; a line identical to one of the
     * last few kept from that tag is dropped, a changed one is kept.
     */
    private static final String[] REPEATING = {"localdisplayadapter", "displaydevicerepository"};
    private static final int REPEAT_WINDOW = 8;
    /**
     * Qualcomm's composer logs ~130 setup lines (modes, colour, HDR, tone mapping) on every
     * connect. Of its info lines only these are kept: hotplugs, the active config and the mode it
     * set, power state, and the link rate. Its warnings and errors are always kept.
     */
    private static final String[] COMPOSER_KEEP = {"HWCSession::", "SubmitDisplayConfig", "[ActiveConfig] Set mode",
            "HWTVDRM::", "SetDisplayState: Set state", "SetPowerMode", "num_configs", "GetSupportedBitClkRate"};
    /**
     * Message fragments of the key display lines, for a long read of the whole log buffer
     * through {@code grep -F} (see {@link #KEY_LINES}): composer hotplugs and modes,
     * SurfaceFlinger's initial mode and pacesetter, display add/remove, native crashes, and HzUnlock's own.
     */
    static final String[] KEY_LINES = {"HWCSession::", "SubmitDisplayConfig", "[ActiveConfig] Set mode", "HWTVDRM::",
            "num_configs", "GetSupportedBitClkRate", "initial mode", "pacesetter", "setDesiredMode: Attempted",
            "Display device added", "Display device removed", "Fatal signal",
            "Process: io.github.npfp222.hzunlock"};
    /** Longer lines (a whole display description with every mode) are cut here. */
    private static final int MAX_LINE = 600;
    /** Message fragments that mark a relevant line whatever its tag. */
    private static final String[] MESSAGES = {"DeX", "desktop mode", "DesktopMode", "hzunlock", "FATAL EXCEPTION"};

    /** threadtime: "MM-DD HH:MM:SS.mmm  PID  TID L TAG: message". */
    private static final Pattern LINE = Pattern.compile(
            "^\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+\\s+\\d+\\s+\\d+\\s+([VDIWEFA])\\s+(.*?)\\s*: (.*)$");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern TOKEN = Pattern.compile("\\b[A-Za-z0-9_\\-]{40,}\\b");

    private LogFilter() {}

    /** The last {@code maxLines} relevant lines of {@code raw}, redacted, oldest first. */
    static String filter(String raw, int maxLines) {
        ArrayDeque<String> kept = new ArrayDeque<>();
        Map<String, ArrayDeque<String>> recent = new HashMap<>();
        boolean inCrash = false;
        for (String line : raw.split("\n")) {
            Matcher match = LINE.matcher(line);
            boolean keep;
            if (match.matches()) {
                String tag = match.group(2).toLowerCase(Locale.ROOT);
                String message = match.group(3);
                // Keep a crash's whole stack trace, which AndroidRuntime logs line by line.
                if (tag.equals("androidruntime") && message.startsWith("FATAL EXCEPTION")) inCrash = true;
                else if (!tag.equals("androidruntime")) inCrash = false;
                keep = inCrash || relevant(match.group(1).charAt(0), tag, message) && !repeated(recent, tag, message);
            } else {
                // A continuation of a multi-line entry: keep it if we're inside a crash.
                keep = inCrash;
            }
            if (!keep) continue;
            String text = line.stripTrailing();
            if (text.length() > MAX_LINE) text = text.substring(0, MAX_LINE) + " …";
            kept.addLast(redact(text));
            while (kept.size() > maxLines) kept.pollFirst();
        }
        return String.join("\n", kept);
    }

    /** True when {@code message} repeats one of the last few kept lines of a {@link #REPEATING} tag. */
    private static boolean repeated(Map<String, ArrayDeque<String>> recent, String tag, String message) {
        boolean repeating = false;
        for (String chatty : REPEATING) {
            if (tag.equals(chatty)) repeating = true;
        }
        if (!repeating) return false;
        ArrayDeque<String> last = recent.computeIfAbsent(tag, t -> new ArrayDeque<>());
        if (last.contains(message)) return true;
        last.addLast(message);
        if (last.size() > REPEAT_WINDOW) last.pollFirst();
        return false;
    }

    /**
     * {@code deep} lines (a long, narrow read, such as {@link #KEY_LINES} over the whole buffer)
     * from before {@code recent} starts, at most {@code maxDeep} of them, then {@code recent}.
     * Both are filtered threadtime text, oldest first.
     */
    static String merge(String deep, String recent, int maxDeep) {
        String start = null;
        for (String line : recent.split("\n")) {
            start = stamp(line);
            if (start != null) break;
        }
        ArrayDeque<String> older = new ArrayDeque<>();
        for (String line : deep.split("\n")) {
            String at = stamp(line);
            if (at == null || start != null && at.compareTo(start) >= 0) continue;
            older.addLast(line);
            while (older.size() > maxDeep) older.pollFirst();
        }
        if (older.isEmpty()) return recent;
        return String.join("\n", older) + (recent.isEmpty() ? "" : "\n" + recent);
    }

    /** The "MM-DD HH:MM:SS.mmm" time of a threadtime line, which sorts as text within a year; else null. */
    private static String stamp(String line) {
        return LINE.matcher(line).matches() ? line.substring(0, 18) : null;
    }

    /** {@link #relevant(String, String)} with the line's level, which keeps the composer's warnings. */
    static boolean relevant(char level, String tagLowerCase, String message) {
        if (tagLowerCase.equals("sdm") && level != 'W' && level != 'E' && level != 'F') {
            boolean key = false;
            for (String fragment : COMPOSER_KEEP) {
                if (message.contains(fragment)) key = true;
            }
            if (!key) return false;
        }
        return relevant(tagLowerCase, message);
    }

    static boolean relevant(String tagLowerCase, String message) {
        if (message.isBlank()) return false;
        for (String noise : NOISE) {
            if (message.startsWith(noise)) return false;
        }
        for (String noise : NOISE_ANYWHERE) {
            if (message.contains(noise)) return false;
        }
        // SurfaceFlinger's per-layer lifecycle lines all start with the layer id, and its composition
        // dumps are a "Display … HWC layers:" header followed by one table row per layer.
        if (tagLowerCase.equals("surfaceflinger") && (message.startsWith("id=") || message.endsWith(" HWC layers:")
                || message.chars().filter(ch -> ch == '|').count() >= 3)) return false;
        for (String exact : EXACT) {
            if (tagLowerCase.equals(exact)) return true;
        }
        for (String ignored : IGNORED) {
            if (tagLowerCase.startsWith(ignored)) return false;
        }
        for (String fragment : TAGS) {
            if (tagLowerCase.contains(fragment)) return true;
        }
        for (String fragment : MESSAGES) {
            if (message.contains(fragment)) return true;
        }
        return false;
    }

    static String redact(String line) {
        String text = EMAIL.matcher(line).replaceAll("<email>");
        return TOKEN.matcher(text).replaceAll("<id>");
    }
}
