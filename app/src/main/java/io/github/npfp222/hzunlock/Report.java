package io.github.npfp222.hzunlock;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Builds the text of compatibility reports and full diagnostics reports. */
final class Report {
    static final String REPO_URL = "https://github.com/npfp222/HzUnlock";

    private Report() {}

    /**
     * The compatibility summary: device, what works, settings, the monitor and its modes, and the
     * slider stops. {@code state} is the service's state, or null when Shizuku isn't connected.
     */
    static String summary(JSONObject state, Prefs prefs) {
        StringBuilder text = new StringBuilder("HzUnlock ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(REPO_URL).append(")\n");
        if (state == null) {
            return text.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ")
                    .append(Build.VERSION.RELEASE).append(" · ").append(Build.DISPLAY).append('\n')
                    .append("(Not connected to Shizuku)").toString();
        }
        text.append(state.optString("device")).append('\n');
        JSONObject caps = state.optJSONObject("caps");
        if (caps != null) {
            mark(text, caps.optBoolean("highRes"), "High-refresh modes");
            mark(text, caps.optBoolean("monitorTiming"), "Full monitor rate");
            mark(text, caps.optBoolean("phoneRates"), "Lower rates");
            mark(text, caps.optBoolean("heat"), "Heat protection");
            text.append("Timing calls: ").append(caps.optString("codes"))
                    .append(caps.optBoolean("monitorTimingVerified") ? " (verified)" : "").append('\n')
                    .append("Access: ").append(caps.optBoolean("root") ? "root" : "ADB shell").append('\n');
        }
        float target = prefs.target();
        text.append("Settings: rate ").append(target <= 0 ? "fastest" : Math.round(target) + " Hz")
                .append(", Fix Slow Connections ").append(prefs.autoReconnect() ? "on" : "off")
                .append(", Cool Down ").append(prefs.coolDown() ? "on" : "off")
                .append(", High Refresh ").append(state.optBoolean("running") ? "on" : "off");
        if (state.has("appHz")) text.append(String.format(Locale.US, " (apps at %.0f Hz)", state.optDouble("appHz")));
        text.append('\n');
        JSONObject monitor = state.optJSONObject("monitor");
        if (monitor != null) {
            text.append(String.format(Locale.US, "Monitor: %s %dx%d @ %.0f Hz (fastest %.0f Hz)\n", monitor.optString("name"),
                    monitor.optInt("width"), monitor.optInt("height"), monitor.optDouble("linkHz"), monitor.optDouble("fastestHz")));
            JSONArray rates = monitor.optJSONArray("rates");
            if (rates != null) {
                text.append(String.format(Locale.US, "Rates at %dx%d: ", monitor.optInt("width"), monitor.optInt("height")))
                        .append(rates.toString().replace(",", ", ")).append('\n');
            }
            if (monitor.has("modes")) text.append("Fastest per resolution: ").append(monitor.optString("modes")).append('\n');
            if (monitor.has("limitedHz")) {
                text.append(String.format(Locale.US, "Connection: stays at %.0f Hz after reconnecting\n", monitor.optDouble("limitedHz")));
            }
        } else {
            text.append("Monitor: none\n");
        }
        JSONArray stops = state.optJSONArray("stops");
        text.append("Stops: ").append(stops == null ? "[]" : stops.toString().replace(",", ", "));
        if (state.has("notice")) text.append("\nNotice: ").append(state.optString("notice"));
        return text.toString();
    }

    private static void mark(StringBuilder text, boolean ok, String name) {
        text.append(ok ? "[x] " : "[ ] ").append(name).append('\n');
    }

    /**
     * The full diagnostics report: the summary, then the session's event log, any saved crashes,
     * and the display-related system log when present. Every part is plain text the user sees
     * before sharing.
     */
    static String full(String summary, JSONObject diagnostics, String appCrash) {
        StringBuilder text = new StringBuilder(summary);
        if (diagnostics != null) {
            JSONArray events = diagnostics.optJSONArray("events");
            section(text, "Events (oldest first)");
            if (events == null || events.length() == 0) text.append("(none)");
            else for (int i = 0; i < events.length(); i++) text.append(events.optString(i)).append('\n');
            String previous = diagnostics.optString("previousEvents");
            if (!previous.isEmpty()) {
                section(text, "Events of the display service's previous run (kept across a reboot)");
                text.append(previous);
            }
            if (diagnostics.has("crash")) {
                section(text, "Display service crash");
                text.append(diagnostics.optString("crash").strip());
            }
        } else {
            section(text, "Events");
            text.append("(Shizuku isn't connected, so the display service's events aren't available.)");
        }
        if (appCrash != null) {
            section(text, "App crash");
            text.append(appCrash.strip());
        }
        if (diagnostics != null && diagnostics.has("systemLog")) {
            section(text, "System log (display-related lines, personal data masked)");
            String log = diagnostics.optString("systemLog");
            text.append(log.isEmpty() ? "(no matching lines)" : log);
        } else if (diagnostics != null && diagnostics.has("systemLogError")) {
            section(text, "System log");
            text.append("Couldn't read it: ").append(diagnostics.optString("systemLogError"));
        }
        return text.toString().strip();
    }

    private static void section(StringBuilder text, String title) {
        text.append("\n\n== ").append(title).append(" ==\n");
    }

    /** The last {@code count} entries of the event log, for a size-limited issue body. */
    static String lastEvents(JSONObject diagnostics, int count) {
        JSONArray events = diagnostics == null ? null : diagnostics.optJSONArray("events");
        if (events == null || events.length() == 0) return "";
        StringBuilder text = new StringBuilder();
        for (int i = Math.max(0, events.length() - count); i < events.length(); i++) {
            text.append(events.optString(i)).append('\n');
        }
        return text.toString().strip();
    }
}
