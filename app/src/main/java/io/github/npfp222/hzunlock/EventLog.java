package io.github.npfp222.hzunlock;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A short, timestamped history of what the display session did, kept in the service process for
 * diagnostics: monitors coming and going, reconnects, rate and timing changes, heat, and errors.
 * Repeats of the same entry are folded into one line so a failing tick can't flood it.
 */
final class EventLog {
    private static final int CAPACITY = 200;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.US);

    private final ArrayDeque<String> entries = new ArrayDeque<>();
    private String lastText;
    private String lastStamp;
    private int repeats;

    synchronized void add(String text) {
        String stamp = LocalDateTime.now().format(TIME);
        if (text.equals(lastText)) {
            repeats++;
            entries.pollLast();
            entries.addLast(lastStamp + "  " + text + "  (×" + (repeats + 1) + ", last " + stamp + ")");
            return;
        }
        lastText = text;
        lastStamp = stamp;
        repeats = 0;
        entries.addLast(stamp + "  " + text);
        while (entries.size() > CAPACITY) entries.pollFirst();
    }

    synchronized List<String> entries() {
        return new ArrayList<>(entries);
    }

    synchronized String text() {
        return String.join("\n", entries);
    }
}
