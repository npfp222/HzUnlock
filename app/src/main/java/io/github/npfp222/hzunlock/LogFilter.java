package io.github.npfp222.hzunlock;

import java.util.ArrayDeque;
import java.util.Locale;
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
    /** Native crash tags, matched exactly. */
    private static final String[] EXACT = {"debug", "libc", "crash_dump64", "tombstoned"};
    /** Tags that contain a fragment above but are unrelated noise. */
    private static final String[] IGNORED = {"dex2oat", "dexopt", "dexoptimizer", "dexmanager_art"};
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
                keep = inCrash || relevant(tag, message);
            } else {
                // A continuation of a multi-line entry: keep it if we're inside a crash.
                keep = inCrash;
            }
            if (!keep) continue;
            kept.addLast(redact(line.stripTrailing()));
            while (kept.size() > maxLines) kept.pollFirst();
        }
        return String.join("\n", kept);
    }

    static boolean relevant(String tagLowerCase, String message) {
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
