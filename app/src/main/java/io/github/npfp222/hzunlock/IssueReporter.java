package io.github.npfp222.hzunlock;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Reporting to GitHub: saves the full report to Downloads as a text file (a file is never cut
 * short, unlike a paste or a prefilled link) and opens a new issue prefilled with a title, short
 * prompts, the phone and monitor, and which file to attach. Used by the main screen's one-tap
 * Report an Issue and by Diagnostics.
 */
final class IssueReporter {
    /**
     * Keep the link short. Signed out, GitHub sends the browser to its login page with the whole
     * link packed inside (encoded a second time), and a long prefilled link then fails with an
     * internal server error after login. The full report travels as the attached file instead.
     */
    private static final int MAX_ISSUE_URL = 1_500;

    /** A report file saved to Downloads, by its content URI and the name it got there. */
    record Saved(Uri uri, String name) {}

    private IssueReporter() {}

    /** Writes {@code report} to Downloads. The app may add its own files there without a permission. */
    static Saved save(Context context, String report) throws IOException {
        String name = "HzUnlock-report-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Downloads isn't available.");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("Couldn't open the file.");
                out.write(report.getBytes(StandardCharsets.UTF_8));
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            // Downloads renames a file whose name is taken; report the name it really has.
            try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) name = cursor.getString(0);
            }
            return new Saved(uri, name);
        } catch (IOException | RuntimeException e) {
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (RuntimeException ignored) {
                    // Nothing more to clean up.
                }
            }
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    /**
     * The new-issue link: a title, the prompts, which file to attach, and the phone and monitor in
     * two lines. {@code fileName} is the saved report, or null when saving failed (then the body
     * asks for a paste, since the report is on the clipboard).
     */
    static String issueUrl(JSONObject state, JSONObject diagnostics, Prefs prefs, String fileName) {
        JSONObject monitor = state == null ? null : state.optJSONObject("monitor");
        String title = "Report: " + Build.MODEL + (monitor != null ? " + " + monitor.optString("name") : "")
                + " (HzUnlock " + BuildConfig.VERSION_NAME + ")";
        String attach = fileName != null
                ? "**Full report:** attach `" + fileName + "` from Downloads (Paste, drop, or click to add files, below).\n\n"
                : "**Full report:** HzUnlock copied it to your clipboard; paste it here.\n\n";
        StringBuilder setup = new StringBuilder();
        for (String line : Report.summary(state, prefs).split("\n")) {
            if (line.startsWith("HzUnlock ") || line.startsWith("Monitor:") || line.contains(" · Android ")) {
                setup.append(line).append('\n');
            }
        }
        String body = "**What happened** (what you expected, and what you saw on the monitor and in the app):\n\n\n"
                + "**Monitor and connection** (for example: Odyssey G40B, USB-C to DisplayPort hub, or direct cable):\n\n\n"
                + attach
                + "```\n" + setup.toString().strip() + "\n```\n";
        String url = Report.REPO_URL + "/issues/new?title=" + Uri.encode(title) + "&body=" + Uri.encode(body);
        // A very long monitor or device name could still push it over; the title and prompts are enough.
        if (url.length() > MAX_ISSUE_URL) {
            url = Report.REPO_URL + "/issues/new?title=" + Uri.encode(title) + "&body=" + Uri.encode(attach);
        }
        return url;
    }

    static void copy(Context context, String report) {
        context.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("HzUnlock report", report));
    }

    static void open(Context context, String url) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Toast.makeText(context, "No browser is available.", Toast.LENGTH_SHORT).show();
        }
    }
}
