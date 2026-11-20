package io.github.npfp222.hzunlock;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;

/** Saves the last crash of the app process so the next launch can offer to report it. */
public final class HzUnlockApp extends Application {
    private static final int MAX_CRASH_BYTES = 64 * 1024;

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            try {
                StringWriter trace = new StringWriter();
                failure.printStackTrace(new PrintWriter(trace));
                try (FileWriter writer = new FileWriter(crashFile(this))) {
                    writer.write("HzUnlock " + BuildConfig.VERSION_NAME + " crashed at " + LocalDateTime.now()
                            + " on thread " + thread.getName() + " (" + Build.MANUFACTURER + " " + Build.MODEL
                            + ", Android " + Build.VERSION.RELEASE + ")\n" + trace);
                }
            } catch (Throwable ignored) {
                // Nothing more to do while the process is going down.
            }
            if (previous != null) previous.uncaughtException(thread, failure);
        });
    }

    static File crashFile(Context context) {
        return new File(context.getFilesDir(), "last-crash.txt");
    }

    /** The saved crash, or null when there is none. */
    static String savedCrash(Context context) {
        File file = crashFile(context);
        if (!file.isFile()) return null;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return new String(bytes, 0, Math.min(bytes.length, MAX_CRASH_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** When the saved crash happened (file time), or 0. */
    static long savedCrashTime(Context context) {
        File file = crashFile(context);
        return file.isFile() ? file.lastModified() : 0;
    }

    static void clearCrash(Context context) {
        File file = crashFile(context);
        if (file.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
