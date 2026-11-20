package io.github.npfp222.hzunlock;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows the full diagnostics report (compatibility summary, the session's event log, saved
 * crashes, and the display-related system log) so users see exactly what they share, and lets
 * them copy it, share it, or open a prefilled GitHub issue. Nothing leaves the phone on its own.
 */
public final class DiagnosticsActivity extends Activity implements ServiceBridge.Listener {
    /** GitHub rejects very long URLs; keep the prefilled issue well below that. */
    private static final int MAX_ISSUE_URL = 7_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private Palette c;
    private Prefs prefs;
    private ServiceBridge bridge;

    private Switch logSwitch;
    private TextView reportView;
    private TextView statusView;
    private View clearRow;
    private View clearSeparator;

    private JSONObject state;
    private JSONObject diagnostics;
    private String report = "";
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        c = Palette.of(this);
        prefs = new Prefs(this);
        bridge = new ServiceBridge(this, this);
        getWindow().setStatusBarColor(c.bg);
        getWindow().setNavigationBarColor(c.bg);
        buildScreen();
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) bars.setSystemBarsAppearance(c.dark ? 0 : light, light);
        // Opening the report counts as having been told about any saved crash.
        prefs.setSeenCrash(HzUnlockApp.savedCrashTime(this));
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
    }

    @Override
    protected void onStop() {
        bridge.release();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        super.onDestroy();
    }

    // ---- data --------------------------------------------------------------------------------

    private void load() {
        loading = true;
        render();
        if (!bridge.connect()) {
            state = null;
            diagnostics = null;
            loading = false;
            render();
        }
    }

    @Override
    public void onConnected(IDisplayService service) {
        boolean includeLog = prefs.includeSystemLog();
        work.execute(() -> {
            JSONObject nextState = null;
            JSONObject nextDiagnostics = null;
            try {
                nextState = new JSONObject(service.getState());
                String raw = service.getDiagnostics(includeLog);
                if (raw != null) nextDiagnostics = new JSONObject(raw);
            } catch (Exception e) {
                // Shown as a partial report below.
            }
            JSONObject s = nextState;
            JSONObject d = nextDiagnostics;
            main.post(() -> {
                state = s;
                diagnostics = d;
                loading = false;
                if (d != null) prefs.setSeenCrash(d.optLong("crashAt"));
                render();
            });
        });
    }

    @Override
    public void onDisconnected() {
        bridge.release();
        loading = false;
        render();
    }

    private void render() {
        if (reportView == null) return;
        String appCrash = HzUnlockApp.savedCrash(this);
        report = Report.full(Report.summary(state, prefs), diagnostics, appCrash);
        reportView.setText(loading ? "Collecting…" : report);
        boolean crashes = appCrash != null || (diagnostics != null && diagnostics.has("crash"));
        clearRow.setVisibility(crashes ? View.VISIBLE : View.GONE);
        clearSeparator.setVisibility(crashes ? View.VISIBLE : View.GONE);
        String status;
        if (loading) status = "Collecting the report…";
        else if (diagnostics == null) status = "Shizuku isn't connected, so this report only has what the app itself knows.";
        else status = "This is everything the report contains. Nothing is sent until you share it.";
        statusView.setText(status);
    }

    // ---- actions -------------------------------------------------------------------------------

    private void copy() {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("HzUnlock report", report));
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show();
    }

    private void share() {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "HzUnlock " + BuildConfig.VERSION_NAME + " report")
                .putExtra(Intent.EXTRA_TEXT, report);
        try {
            startActivity(Intent.createChooser(send, "Share Report"));
        } catch (RuntimeException e) {
            Toast.makeText(this, "No app can share the report.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Copies the full report, then opens a new GitHub issue prefilled with the summary and the
     * latest events. The full report is usually too long for a link, so the issue asks for a paste.
     */
    private void reportIssue() {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("HzUnlock report", report));
        String summary = Report.summary(state, prefs);
        String url = null;
        for (int events : new int[]{40, 25, 12, 0}) {
            String recent = Report.lastEvents(diagnostics, events);
            String body = "**Compatibility report**\n```\n" + summary + "\n```\n\n"
                    + (recent.isEmpty() ? "" : "**Latest events**\n```\n" + recent + "\n```\n\n")
                    + "**Full diagnostics** (HzUnlock copied them to your clipboard; paste them here):\n\n\n"
                    + "**Monitor and connection** (for example: Odyssey G40B, USB-C to DisplayPort hub):\n\n\n"
                    + "**What happened:**\n";
            url = Report.REPO_URL + "/issues/new?body=" + Uri.encode(body);
            if (url.length() <= MAX_ISSUE_URL) break;
        }
        Toast.makeText(this, "Full report copied. Paste it into the issue.", Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Toast.makeText(this, "No browser is available.", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearCrashes() {
        HzUnlockApp.clearCrash(this);
        IDisplayService service = bridge.service();
        if (service != null) {
            work.execute(() -> {
                try {
                    service.clearCrash();
                } catch (Exception ignored) {
                    // The next load shows whether it's gone.
                }
                main.post(() -> {
                    if (bridge.service() == service) onConnected(service);
                });
            });
        } else {
            render();
        }
    }

    // ---- layout ----------------------------------------------------------------------------

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(c.bg);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        FrameLayout frame = new FrameLayout(this);
        scroll.addView(frame);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(12), dp(20), dp(32));
        frame.addView(column, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
        frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.min(r - l, dp(720));
            if (column.getLayoutParams().width != width) {
                column.getLayoutParams().width = width;
                column.requestLayout();
            }
        });
        setContentView(scroll);

        TextView back = label(column, "‹ HzUnlock", 17, c.link, Typeface.NORMAL);
        back.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        back.setPadding(0, dp(8), dp(12), dp(8));
        back.setContentDescription("Back to HzUnlock");
        back.setOnClickListener(v -> finish());
        TextView title = label(column, "Diagnostics", 34, c.label, Typeface.BOLD);
        title.setPadding(dp(4), dp(4), 0, 0);
        statusView = label(column, "", 13, c.secondary, Typeface.NORMAL);
        statusView.setPadding(dp(16), 0, dp(16), 0);
        top(statusView, 8);

        LinearLayout options = group(column, 18);
        logSwitch = new Switch(this);
        switchRow(options, "Include System Log", logSwitch);
        logSwitch.setChecked(prefs.includeSystemLog());
        logSwitch.setOnCheckedChangeListener((button, on) -> {
            prefs.setIncludeSystemLog(on);
            bridge.release();
            load();
        });
        TextView logNote = label(column, "Adds recent display, DeX, and crash lines from Android's log. "
                + "E-mail addresses and long IDs are masked.", 13, c.secondary, Typeface.NORMAL);
        logNote.setPadding(dp(16), 0, dp(16), 0);
        top(logNote, 8);

        LinearLayout actions = group(column, 22);
        linkRow(actions, "Report an Issue on GitHub", v -> reportIssue());
        separator(actions);
        linkRow(actions, "Copy Report", v -> copy());
        separator(actions);
        linkRow(actions, "Share Report…", v -> share());
        separator(actions);
        linkRow(actions, "Refresh", v -> {
            bridge.release();
            load();
        });
        clearSeparator = separator(actions);
        clearRow = linkRow(actions, "Clear Saved Crashes", v -> clearCrashes());

        header(column, "REPORT");
        LinearLayout card = group(column, 6);
        HorizontalScrollView sideways = new HorizontalScrollView(this);
        sideways.setHorizontalScrollBarEnabled(false);
        card.addView(sideways, new LinearLayout.LayoutParams(-1, -2));
        reportView = new TextView(this);
        reportView.setTextSize(11);
        reportView.setTextColor(c.label);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        reportView.setPadding(dp(14), dp(12), dp(14), dp(12));
        sideways.addView(reportView, new FrameLayout.LayoutParams(-2, -2));
        render();
    }

    private LinearLayout group(LinearLayout parent, int topDp) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(c.card);
        shape.setCornerRadius(dp(16));
        group.setBackground(shape);
        group.setClipToOutline(true);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(topDp);
        parent.addView(group, layout);
        return group;
    }

    private void header(LinearLayout parent, String text) {
        TextView view = label(parent, text, 13, c.secondary, Typeface.NORMAL);
        view.setPadding(dp(16), 0, dp(16), 0);
        top(view, 30);
    }

    private TextView label(ViewGroup parent, String text, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        view.setLineSpacing(0, 1.12f);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private void top(View view, int dp) {
        ((LinearLayout.LayoutParams) view.getLayoutParams()).topMargin = dp(dp);
    }

    private View separator(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(c.separator);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
        layout.leftMargin = dp(16);
        parent.addView(line, layout);
        return line;
    }

    private void switchRow(LinearLayout parent, String title, Switch toggle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(12), 0);
        row.setMinimumHeight(dp(52));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        TextView text = label(row, title, 17, c.label, Typeface.NORMAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        int[][] states = {{android.R.attr.state_checked}, {}};
        toggle.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        toggle.setTrackTintList(new ColorStateList(states, new int[]{c.accent, c.fill}));
        toggle.setTrackTintMode(PorterDuff.Mode.SRC);
        toggle.setContentDescription(title);
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(v -> toggle.toggle());
    }

    private View linkRow(LinearLayout parent, String title, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setMinimumHeight(dp(52));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), null,
                new ColorDrawable(Color.WHITE)));
        row.setOnClickListener(click);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        TextView text = label(row, title, 17, c.accent, Typeface.NORMAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
