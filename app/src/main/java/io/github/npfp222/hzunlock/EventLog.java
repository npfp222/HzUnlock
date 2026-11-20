package io.github.npfp222.hzunlock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 *
 * <p>With {@link #persistTo}, each new entry is also appended to a file and synced to storage, so
 * the history survives a phone that freezes and has to be rebooted.
 */
final class EventLog {
    private static final int CAPACITY = 200;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.US);

    private final ArrayDeque<String> entries = new ArrayDeque<>();
    private String lastText;
    private String lastStamp;
    private int repeats;
    private File file;
    private int linesInFile;

    /** Also keeps the log in {@code file}, starting it afresh. Failures only turn this off. */
    synchronized void persistTo(File target) {
        file = target;
        rewriteFile();
    }

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
        if (file == null) return;
        if (linesInFile >= CAPACITY * 4) rewriteFile();
        else write(stamp + "  " + text + "\n", true);
    }

    private void rewriteFile() {
        StringBuilder text = new StringBuilder();
        for (String entry : entries) text.append(entry).append('\n');
        linesInFile = 0;
        write(text.toString(), false);
        linesInFile = entries.size();
    }

    private void write(String text, boolean append) {
        try (FileOutputStream out = new FileOutputStream(file, append)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
            if (append) linesInFile++;
        } catch (IOException e) {
            file = null;
        }
    }

    synchronized List<String> entries() {
        return new ArrayList<>(entries);
    }

    synchronized String text() {
        return String.join("\n", entries);
    }
}
