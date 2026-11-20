package io.github.npfp222.hzunlock;

import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.system.Os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;

/** Privileged implementation that Shizuku loads as ADB shell or root, separate from the app process. */
public final class DisplayService extends IDisplayService.Stub {
    static final int ROOT_UID = 0;
    static final int SHELL_UID = 2000;
    /** The last crash of this service. /data/local/tmp is writable for both ADB shell and root. */
    private static final File CRASH_FILE = new File("/data/local/tmp/hzunlock-crash.txt");
    private static final int MAX_CRASH_BYTES = 64 * 1024;
    /** The event log of this service, and of the one before it (which survives a reboot after a freeze). */
    private static final File EVENTS_FILE = new File("/data/local/tmp/hzunlock-events.txt");
    private static final File PREVIOUS_EVENTS_FILE = new File("/data/local/tmp/hzunlock-events-previous.txt");
    private static final int SYSTEM_LOG_LINES = 250;

    private final int ownerUid;
    private final int serviceUid = Os.getuid();
    private final RefreshSession session;
    private final String device;

    public DisplayService(Context context) {
        ownerUid = context.getApplicationInfo().uid;
        DisplayControl.init(context);
        session = new RefreshSession(serviceUid == ROOT_UID);
        device = describeDevice();
        installCrashHandler();
        keepEventLog();
        session.log().add("Display service started (HzUnlock " + BuildConfig.VERSION_NAME + ", uid " + serviceUid + ").");
    }

    /** Saves any crash of this process, with the recent events, so the app can report it later. */
    private void installCrashHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            try {
                StringWriter trace = new StringWriter();
                failure.printStackTrace(new PrintWriter(trace));
                try (FileWriter writer = new FileWriter(CRASH_FILE)) {
                    writer.write("HzUnlock " + BuildConfig.VERSION_NAME + " display service crashed at " + LocalDateTime.now()
                            + " on thread " + thread.getName() + "\n" + trace + "\nRecent events:\n" + session.log().text() + "\n");
                }
            } catch (Throwable ignored) {
                // Nothing more to do while the process is going down.
            }
            if (previous != null) previous.uncaughtException(thread, failure);
            else System.exit(10);
        });
    }

    /** Keeps the last service's events as the previous log, then records this service's events to a file. */
    private void keepEventLog() {
        if (EVENTS_FILE.isFile() && EVENTS_FILE.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            PREVIOUS_EVENTS_FILE.delete();
            //noinspection ResultOfMethodCallIgnored
            EVENTS_FILE.renameTo(PREVIOUS_EVENTS_FILE);
        }
        session.log().persistTo(EVENTS_FILE);
    }

    private static String describeDevice() {
        String text = Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE;
        try {
            int oneUi = Integer.parseInt(DisplayControl.run(2, "/system/bin/getprop", "ro.build.version.oneui"));
            text += String.format(java.util.Locale.US, " · One UI %d.%d", oneUi / 10000, (oneUi / 100) % 100);
        } catch (Exception ignored) {
            // Not a Samsung build, or the property is missing.
        }
        return text + " · " + Build.DISPLAY;
    }

    @Override
    public int getServiceUid() {
        requireOwner();
        return serviceUid;
    }

    @Override
    public String getState() {
        requireOwner();
        long identity = Binder.clearCallingIdentity();
        try {
            JSONObject state = session.state();
            state.put("uid", serviceUid);
            state.put("device", device);
            if (CRASH_FILE.isFile()) state.put("crashAt", CRASH_FILE.lastModified());
            return state.toString();
        } catch (Exception e) {
            return "{\"notice\":" + JSONObject.quote(DisplayControl.describe(e)) + "}";
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public String startBoost(float targetHz, boolean autoReconnect, boolean coolDown) {
        requireOwner();
        if (!privileged()) return "HzUnlock needs Shizuku running as ADB shell or root.";
        long identity = Binder.clearCallingIdentity();
        try {
            return session.start(targetHz, autoReconnect, coolDown);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public String stopBoost() {
        requireOwner();
        long identity = Binder.clearCallingIdentity();
        try {
            return session.stop(true);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void configure(float targetHz, boolean autoReconnect, boolean coolDown) {
        requireOwner();
        long identity = Binder.clearCallingIdentity();
        try {
            session.configure(targetHz, autoReconnect, coolDown);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public String reconnectMonitor() {
        requireOwner();
        if (!privileged()) return "Reconnecting needs Shizuku running as ADB shell or root.";
        long identity = Binder.clearCallingIdentity();
        try {
            return session.reconnect();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public String getDiagnostics(boolean includeSystemLog) {
        requireOwner();
        long identity = Binder.clearCallingIdentity();
        try {
            JSONObject out = new JSONObject();
            out.put("events", new JSONArray(session.log().entries()));
            if (PREVIOUS_EVENTS_FILE.isFile()) {
                out.put("previousEventsAt", PREVIOUS_EVENTS_FILE.lastModified());
                out.put("previousEvents", readTail(PREVIOUS_EVENTS_FILE));
            }
            if (CRASH_FILE.isFile()) {
                out.put("crashAt", CRASH_FILE.lastModified());
                out.put("crash", readCrash());
            }
            if (includeSystemLog) {
                try {
                    String raw = DisplayControl.run(8, "/system/bin/logcat", "-d", "-b", "main,system,crash",
                            "-v", "threadtime", "-t", "8000");
                    out.put("systemLog", LogFilter.filter(raw, SYSTEM_LOG_LINES));
                } catch (Exception e) {
                    out.put("systemLogError", DisplayControl.describe(e));
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "{\"error\":" + JSONObject.quote(DisplayControl.describe(e)) + "}";
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static String readCrash() throws IOException {
        byte[] bytes = Files.readAllBytes(CRASH_FILE.toPath());
        int length = Math.min(bytes.length, MAX_CRASH_BYTES);
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    /** The end of a text file, at most {@link #MAX_CRASH_BYTES}, starting at a whole line. */
    private static String readTail(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        int start = Math.max(0, bytes.length - MAX_CRASH_BYTES);
        String text = new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
        int newline = start > 0 ? text.indexOf('\n') : -1;
        return (newline >= 0 ? text.substring(newline + 1) : text).strip();
    }

    @Override
    public void clearCrash() {
        requireOwner();
        long identity = Binder.clearCallingIdentity();
        try {
            if (CRASH_FILE.isFile() && !CRASH_FILE.delete()) session.log().add("Couldn't delete the saved crash report.");
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void destroy() {
        int caller = Binder.getCallingUid();
        if (caller != serviceUid && caller != ownerUid) {
            throw new SecurityException("This caller cannot stop the display service.");
        }
        Binder.clearCallingIdentity();
        try {
            // The service is going away (an update, or Shizuku stopping), not the user turning
            // high refresh off, so leave DeX's modes as they are.
            session.stop(false);
        } finally {
            System.exit(0);
        }
    }

    private boolean privileged() {
        return serviceUid == ROOT_UID || serviceUid == SHELL_UID;
    }

    private void requireOwner() {
        if (Binder.getCallingUid() != ownerUid) {
            throw new SecurityException("This service accepts requests from HzUnlock only.");
        }
    }
}
