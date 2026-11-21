package io.github.npfp222.hzunlock;

import android.animation.LayoutTransition;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity implements ServiceBridge.Listener {
    static final String ACTION_TOGGLE = "io.github.npfp222.hzunlock.TOGGLE";
    private static final int PERMISSION = 32;
    private static final String AUTHOR = "npfp222";
    private static final String AUTHOR_URL = "https://github.com/npfp222";
    private static final String REPO_URL = Report.REPO_URL;
    private static final int[] DEFAULT_STOPS = {24, 30, 48, 60, 80, 120, 240};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private Palette c;
    private Prefs prefs;
    private ServiceBridge bridge;

    private ScrollView screen;
    private LinearLayout setupGroup;
    private TextView setupTitle;
    private TextView setupBody;
    private TextView setupButton;
    private TextView bigNumber;
    private TextView monitorLine;
    private SeekBar slider;
    private LinearLayout sliderEnds;
    private TextView minLabel;
    private TextView maxLabel;
    private TextView sliderHint;
    private Switch powerSwitch;
    private TextView statusLine;
    private LinearLayout reconnectGroup;
    private TextView reconnectText;
    private TextView reconnectButton;
    private LinearLayout modeGroup;
    private TextView modeTitle;
    private TextView modeText;
    private TextView modePrimary;
    private LinearLayout modeButtons;
    private TextView modeChoose;
    private TextView modeUndo;
    private TextView modeDefault;
    private Switch restoreSwitch;
    private Switch coolSwitch;
    private Switch fixSwitch;
    private Switch updateSwitch;
    private View reconnectRow;
    private TextView compatibilityValue;
    private LinearLayout compatibilityGroup;
    private LinearLayout compatibilityList;
    private TextView version;

    private JSONObject state;
    private int[] stops = DEFAULT_STOPS;
    private boolean busy;
    private boolean visible;
    private boolean dragging;
    private boolean restoredThisConnection;
    private boolean pendingToggle;
    private boolean updatingViews;
    private String message;
    private AlertDialog dialog;
    /** The Monitor Mode switch phase last shown, to bring the card into view when it needs the user. */
    private String lastModePhase;
    private UpdateChecker.Update pendingUpdate;
    private boolean checkingUpdate;
    private Runnable setupAction = () -> {};

    private final Shizuku.OnBinderReceivedListener received = () -> main.post(this::connect);
    private final Shizuku.OnBinderDeadListener dead = () -> main.post(this::onDisconnected);
    private final Shizuku.OnRequestPermissionResultListener permission = (code, result) -> main.post(this::connect);

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            refresh();
            main.postDelayed(this, 1_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        c = new Palette(dark);
        prefs = new Prefs(this);
        bridge = new ServiceBridge(this, this);
        getWindow().setStatusBarColor(c.bg);
        getWindow().setNavigationBarColor(c.bg);
        buildScreen();
        // The insets controller exists only once the content view (and its decor) is set.
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) bars.setSystemBarsAppearance(dark ? 0 : light, light);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            pendingToggle = true;
            if (bridge.service() != null && state != null) runPendingToggle();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(dead);
        Shizuku.addRequestPermissionResultListener(permission);
        connect();
        poll.run();
        alertCrash();
        showPendingUpdate();
        checkForUpdate(false);
    }

    @Override
    protected void onStop() {
        visible = false;
        main.removeCallbacks(poll);
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(dead);
        Shizuku.removeRequestPermissionResultListener(permission);
        bridge.release();
        restoredThisConnection = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (dialog != null) dialog.dismiss();
        commands.shutdownNow();
        network.shutdownNow();
        super.onDestroy();
    }

    // ---- service ---------------------------------------------------------------------------

    private void connect() {
        if (!visible) return;
        switch (ServiceBridge.access(this)) {
            case NOT_INSTALLED -> showSetup("Install Shizuku",
                    "HzUnlock talks to the display system through Shizuku. Install it, then start it with Wireless debugging.",
                    "Get Shizuku", () -> openUri("https://shizuku.rikka.app/download/"));
            case NOT_RUNNING -> showSetup("Start Shizuku",
                    "Open Shizuku and start it with Wireless debugging. HzUnlock connects automatically.",
                    "Open Shizuku", this::openShizuku);
            case OUTDATED -> showSetup("Update Shizuku", "HzUnlock needs Shizuku 13 or later.",
                    "Open Shizuku", this::openShizuku);
            case NO_PERMISSION -> showSetup("Allow Access",
                    "Approve HzUnlock in Shizuku so it can manage your monitor's refresh rate.",
                    "Allow", () -> Shizuku.requestPermission(PERMISSION));
            case READY -> {
                if (bridge.service() == null) {
                    showSetup("Connecting", "Starting HzUnlock's display service…", "Retry", () -> {
                        bridge.release();
                        connect();
                    });
                    bridge.connect();
                }
            }
        }
        render();
    }

    @Override
    public void onConnected(IDisplayService service) {
        commands.execute(() -> {
            try {
                prefs.push(service);
                JSONObject snapshot = new JSONObject(service.getState());
                // A session that turned itself off to undo a monitor mode switch stays off.
                if (snapshot.has("rolledBack")) prefs.setWantOn(false);
                boolean restore = !restoredThisConnection && prefs.restore() && prefs.wantOn()
                        && !snapshot.optBoolean("running") && !pendingToggle;
                if (restore) {
                    prefs.start(service);
                    snapshot = new JSONObject(service.getState());
                }
                JSONObject result = snapshot;
                main.post(() -> {
                    restoredThisConnection = true;
                    apply(service, result);
                    runPendingToggle();
                });
            } catch (Exception e) {
                main.post(this::onDisconnected);
            }
        });
    }

    @Override
    public void onDisconnected() {
        bridge.release();
        state = null;
        busy = false;
        connect();
    }

    private void refresh() {
        IDisplayService service = bridge.service();
        if (service == null) {
            if (visible && setupGroup.getVisibility() != View.VISIBLE) connect();
            return;
        }
        commands.execute(() -> {
            try {
                JSONObject snapshot = new JSONObject(service.getState());
                main.post(() -> apply(service, snapshot));
            } catch (Exception e) {
                main.post(this::onDisconnected);
            }
        });
    }

    private void apply(IDisplayService service, JSONObject snapshot) {
        if (bridge.service() != service) return;
        if (snapshot.has("rolledBack") && prefs.wantOn()) prefs.setWantOn(false);
        state = snapshot;
        setupGroup.setVisibility(View.GONE);
        render();
        alertCrash();
    }

    private void command(Command command) {
        IDisplayService service = bridge.service();
        if (service == null || busy) return;
        busy = true;
        message = null;
        render();
        commands.execute(() -> {
            String result;
            try {
                result = command.run(service);
            } catch (Exception e) {
                result = Errors.describe(e);
            }
            String text = result;
            main.post(() -> {
                busy = false;
                // Successful actions speak for themselves (a running reconnect shows its own live
                // status from the service). Anything else is shown briefly, then cleared.
                boolean silent = text == null || text.startsWith("High refresh is") || text.startsWith("Reconnecting")
                        || text.startsWith("Switching to") || text.startsWith("Undoing") || text.equals("Kept.")
                        || text.startsWith("Cancelled") || text.startsWith("Back to the monitor's own mode");
                message = silent ? null : text;
                if (message != null) {
                    String shown = message;
                    main.postDelayed(() -> {
                        if (shown.equals(message)) {
                            message = null;
                            render();
                        }
                    }, 6_000);
                }
                refresh();
            });
        });
    }

    private void runPendingToggle() {
        if (!pendingToggle || bridge.service() == null) return;
        pendingToggle = false;
        boolean running = state != null && state.optBoolean("running");
        command(running ? prefs::stop : prefs::start);
    }

    private void pushSettings() {
        IDisplayService service = bridge.service();
        if (service == null) return;
        commands.execute(() -> {
            try {
                prefs.push(service);
            } catch (Exception ignored) {
                // The next poll reports a lost service.
            }
            main.post(this::refresh);
        });
    }

    // ---- layout ----------------------------------------------------------------------------

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        screen = scroll;
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
        column.setLayoutTransition(smooth());
        frame.addView(column, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
        frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.min(r - l, dp(560));
            if (column.getLayoutParams().width != width) {
                column.getLayoutParams().width = width;
                column.requestLayout();
            }
        });
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.BOTTOM);
        header.setPadding(dp(4), dp(16), 0, 0);
        column.addView(header, new LinearLayout.LayoutParams(-1, -2));
        TextView title = label(header, "HzUnlock", 34, c.label, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        TextView credit = label(header, "by " + AUTHOR, 15, c.link, Typeface.NORMAL);
        LinearLayout.LayoutParams creditLayout = new LinearLayout.LayoutParams(-2, -2);
        creditLayout.leftMargin = dp(10);
        credit.setLayoutParams(creditLayout);
        // Sit on the title's baseline area and stay comfortably tappable.
        credit.setPadding(dp(4), dp(8), dp(4), dp(7));
        Drawable mark = getDrawable(R.drawable.ic_github).mutate();
        mark.setTint(c.link);
        mark.setBounds(0, 0, dp(16), dp(16));
        credit.setCompoundDrawablesRelative(null, null, mark, null);
        credit.setCompoundDrawablePadding(dp(6));
        credit.setContentDescription("By " + AUTHOR + ", open HzUnlock on GitHub");
        credit.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), null,
                rounded(Color.WHITE, 8)));
        credit.setOnClickListener(v -> openUri(REPO_URL));

        // Setup (only while Shizuku isn't ready)
        setupGroup = group(column, 18);
        setupGroup.setPadding(dp(20), dp(20), dp(20), dp(20));
        setupTitle = label(setupGroup, "", 20, c.label, Typeface.BOLD);
        setupBody = label(setupGroup, "", 15, c.secondary, Typeface.NORMAL);
        top(setupBody, 6);
        setupButton = pillButton(setupGroup, v -> setupAction.run());
        top(setupButton, 18);

        // Rate
        LinearLayout hero = group(column, 18);
        hero.setPadding(dp(20), dp(28), dp(20), dp(18));
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setLayoutTransition(smooth());
        label(hero, "App refresh target", 15, c.secondary, Typeface.NORMAL).setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout numberRow = new LinearLayout(this);
        numberRow.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hero.addView(numberRow, new LinearLayout.LayoutParams(-2, -2));
        bigNumber = new TextView(this);
        bigNumber.setTextSize(88);
        bigNumber.setIncludeFontPadding(false);
        bigNumber.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        bigNumber.setLetterSpacing(-0.02f);
        numberRow.addView(bigNumber);
        TextView unit = new TextView(this);
        unit.setText("Hz");
        unit.setTextSize(24);
        unit.setTextColor(c.secondary);
        unit.setPadding(dp(6), 0, 0, dp(14));
        numberRow.addView(unit);
        monitorLine = label(hero, "", 15, c.secondary, Typeface.NORMAL);
        monitorLine.setGravity(Gravity.CENTER_HORIZONTAL);
        top(monitorLine, 4);

        slider = new SeekBar(this);
        styleSlider(slider);
        LinearLayout.LayoutParams sliderLayout = new LinearLayout.LayoutParams(-1, dp(44));
        sliderLayout.topMargin = dp(22);
        hero.addView(slider, sliderLayout);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || !canSetRate() || progress >= stops.length) return;
                bar.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                bigNumber.setText(String.valueOf(stops[progress]));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                boolean apply = dragging && canSetRate();
                dragging = false;
                if (apply) commitRate(stops[bar.getProgress()]);
                else render();
            }
        });
        sliderEnds = new LinearLayout(this);
        sliderEnds.setPadding(dp(4), 0, dp(4), 0);
        hero.addView(sliderEnds, new LinearLayout.LayoutParams(-1, -2));
        minLabel = label(sliderEnds, "", 13, c.secondary, Typeface.NORMAL);
        minLabel.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        maxLabel = label(sliderEnds, "", 13, c.secondary, Typeface.NORMAL);
        maxLabel.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        maxLabel.setGravity(Gravity.END);
        // While High Refresh is off DeX runs its standard modes, so the stops would be the wrong
        // ones and a rate couldn't apply; the slider is dimmed and says why.
        sliderHint = label(hero, "Turn on High Refresh to set the rate.", 13, c.secondary, Typeface.NORMAL);
        sliderHint.setGravity(Gravity.CENTER_HORIZONTAL);
        top(sliderHint, 6);
        sliderHint.setVisibility(View.GONE);

        // Power
        LinearLayout power = group(column, 18);
        powerSwitch = new Switch(this);
        switchRow(power, "High Refresh", powerSwitch, on -> {
            powerSwitch.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            command(on ? prefs::start : prefs::stop);
        });
        statusLine = footer(column);

        // Slow connection
        reconnectGroup = group(column, 18);
        reconnectGroup.setPadding(dp(16), dp(14), dp(16), dp(14));
        reconnectText = label(reconnectGroup, "", 15, c.label, Typeface.NORMAL);
        reconnectButton = label(reconnectGroup, "Reconnect Monitor", 17, c.accent, Typeface.NORMAL);
        top(reconnectButton, 10);
        reconnectButton.setOnClickListener(v -> command(IDisplayService::reconnectMonitor));

        // Monitor mode (root, or a Snapdragon phone without root). Separate from the slider: this
        // changes the signal sent to the monitor, the slider only the rate apps draw at.
        modeGroup = group(column, 18);
        modeGroup.setPadding(dp(16), dp(14), dp(16), dp(14));
        modeGroup.setLayoutTransition(smooth());
        modeTitle = label(modeGroup, "Monitor Mode", 17, c.label, Typeface.BOLD);
        modeText = label(modeGroup, "", 15, c.secondary, Typeface.NORMAL);
        top(modeText, 6);
        // The one action that matters now: Start at the suggested mode, or Keep.
        modePrimary = pillButton(modeGroup, v -> {});
        top(modePrimary, 14);
        modeButtons = new LinearLayout(this);
        modeGroup.addView(modeButtons, new LinearLayout.LayoutParams(-1, -2));
        top(modeButtons, 12);
        modeChoose = label(modeButtons, "Choose Mode…", 17, c.accent, Typeface.NORMAL);
        LinearLayout.LayoutParams chooseLayout = new LinearLayout.LayoutParams(-2, -2);
        chooseLayout.rightMargin = dp(28);
        modeChoose.setLayoutParams(chooseLayout);
        modeChoose.setOnClickListener(v -> chooseMonitorMode());
        modeUndo = label(modeButtons, "Undo", 17, c.accent, Typeface.NORMAL);
        LinearLayout.LayoutParams undoLayout = new LinearLayout.LayoutParams(-2, -2);
        undoLayout.rightMargin = dp(28);
        modeUndo.setLayoutParams(undoLayout);
        modeUndo.setOnClickListener(v -> command(s -> s.confirmMonitorMode(false)));
        modeDefault = label(modeButtons, "Use Default", 17, c.accent, Typeface.NORMAL);
        modeDefault.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        modeDefault.setOnClickListener(v -> command(IDisplayService::forgetMonitorStartMode));
        modeGroup.setVisibility(View.GONE);

        // Settings
        header(column, "SETTINGS");
        LinearLayout settings = group(column, 6);
        restoreSwitch = new Switch(this);
        switchRow(settings, "Restore Automatically", restoreSwitch, prefs::setRestore);
        separator(settings);
        coolSwitch = new Switch(this);
        switchRow(settings, "Cool Down When Hot", coolSwitch, on -> {
            prefs.setCoolDown(on);
            pushSettings();
        });
        separator(settings);
        fixSwitch = new Switch(this);
        switchRow(settings, "Fix Slow Connections", fixSwitch, on -> {
            prefs.setAutoReconnect(on);
            pushSettings();
        });
        separator(settings);
        updateSwitch = new Switch(this);
        switchRow(settings, "Check for Updates", updateSwitch, on -> {
            prefs.setUpdateCheck(on);
            if (on) checkForUpdate(false);
        });
        restoreSwitch.setChecked(prefs.restore());
        coolSwitch.setChecked(prefs.coolDown());
        fixSwitch.setChecked(prefs.autoReconnect());
        updateSwitch.setChecked(prefs.updateCheck());
        TextView settingsNote = footer(column);
        settingsNote.setText("Check for Updates asks GitHub once a day and lets you know about major updates. "
                + "Check for Updates under About shows every update. Nothing about your phone or monitor is sent.");

        // More
        LinearLayout more = group(column, 22);
        linkRow(more, "Add Quick Settings Tile", null, v -> addTile());
        separator(more);
        reconnectRow = linkRow(more, "Reconnect Monitor", null, v -> command(IDisplayService::reconnectMonitor));
        separator(more);
        compatibilityValue = new TextView(this);
        linkRow(more, "Compatibility", compatibilityValue, v -> toggleCompatibility());
        separator(more);
        // One tap: saves the full report to Downloads and opens a prefilled GitHub issue.
        linkRow(more, "Report an Issue", null, v -> reportIssue());
        separator(more);
        linkRow(more, "Diagnostics", null, v -> openDiagnostics());

        // Compatibility details (collapsed)
        compatibilityGroup = group(column, 12);
        compatibilityGroup.setPadding(dp(16), dp(6), dp(16), dp(6));
        compatibilityList = new LinearLayout(this);
        compatibilityList.setOrientation(LinearLayout.VERTICAL);
        compatibilityGroup.addView(compatibilityList, new LinearLayout.LayoutParams(-1, -2));
        // Reports live in one place only: Diagnostics (summary, events, logs, Report an Issue).
        compatibilityGroup.setVisibility(View.GONE);

        // About
        header(column, "ABOUT");
        LinearLayout about = group(column, 6);
        TextView developer = new TextView(this);
        developer.setText(AUTHOR);
        linkRow(about, "Developer", developer, v -> openUri(AUTHOR_URL));
        separator(about);
        TextView source = new TextView(this);
        source.setText("GitHub");
        linkRow(about, "Source Code", source, v -> openUri(REPO_URL));
        separator(about);
        TextView installed = new TextView(this);
        installed.setText(BuildConfig.VERSION_NAME);
        linkRow(about, "Check for Updates", installed, v -> checkForUpdate(true));

        version = label(column, "", 13, c.secondary, Typeface.NORMAL);
        version.setGravity(Gravity.CENTER_HORIZONTAL);
        top(version, 24);
        render();
    }

    private LayoutTransition smooth() {
        LayoutTransition transition = new LayoutTransition();
        transition.enableTransitionType(LayoutTransition.CHANGING);
        transition.setDuration(220);
        return transition;
    }

    private LinearLayout group(LinearLayout parent, int topDp) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(rounded(c.card, 16));
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

    private TextView footer(LinearLayout parent) {
        TextView view = label(parent, "", 13, c.secondary, Typeface.NORMAL);
        view.setPadding(dp(16), 0, dp(16), 0);
        top(view, 8);
        return view;
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

    private void separator(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(c.separator);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
        layout.leftMargin = dp(16);
        parent.addView(line, layout);
    }

    private interface Toggle {
        void changed(boolean on);
    }

    private void switchRow(LinearLayout parent, String title, Switch toggle, Toggle changed) {
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
        toggle.setOnCheckedChangeListener((button, on) -> {
            if (!updatingViews) changed.changed(on);
        });
        row.setOnClickListener(v -> {
            if (toggle.isEnabled()) toggle.toggle();
        });
    }

    private View linkRow(LinearLayout parent, String title, TextView value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setMinimumHeight(dp(52));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), null,
                new ColorDrawable(Color.WHITE)));
        row.setOnClickListener(click);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        TextView text = label(row, title, 17, c.label, Typeface.NORMAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        if (value != null) {
            value.setTextSize(17);
            value.setTextColor(c.secondary);
            value.setPadding(0, 0, dp(8), 0);
            row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        }
        TextView chevron = label(row, "›", 24, c.separator, Typeface.NORMAL);
        chevron.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private TextView pillButton(LinearLayout parent, View.OnClickListener click) {
        TextView button = label(parent, "", 17, Color.WHITE, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(50));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                rounded(c.accent, 14), null));
        button.setOnClickListener(click);
        return button;
    }

    private void styleSlider(SeekBar bar) {
        float radius = dp(3);
        GradientDrawable track = new GradientDrawable();
        track.setColor(c.fill);
        track.setCornerRadius(radius);
        GradientDrawable filled = new GradientDrawable();
        filled.setColor(c.accent);
        filled.setCornerRadius(radius);
        LayerDrawable progress = new LayerDrawable(new Drawable[]{track, new ClipDrawable(filled, Gravity.START, ClipDrawable.HORIZONTAL)});
        progress.setId(0, android.R.id.background);
        progress.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            progress.setLayerHeight(i, dp(6));
            progress.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        bar.setProgressDrawable(progress);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.WHITE);
        thumb.setStroke(dp(1) / 2 + 1, c.separator);
        thumb.setSize(dp(28), dp(28));
        bar.setThumb(thumb);
        bar.setThumbOffset(dp(14));
        bar.setSplitTrack(false);
        bar.setBackground(null);
        bar.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable tick = new GradientDrawable();
        tick.setShape(GradientDrawable.OVAL);
        tick.setColor(c.secondary);
        tick.setSize(dp(4), dp(4));
        bar.setTickMark(tick);
        bar.setContentDescription("App refresh target");
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---- rendering ---------------------------------------------------------------------------

    private void render() {
        if (bigNumber == null) return;
        boolean connected = bridge.service() != null && state != null;
        boolean running = connected && state.optBoolean("running");
        JSONObject monitor = state == null ? null : state.optJSONObject("monitor");
        JSONObject caps = state == null ? null : state.optJSONObject("caps");

        // Slider stops come from the hardware: the monitor's rate plus the phone panel's slower rates.
        boolean rateReady = rateOptionsReady();
        if (!rateReady) dragging = false;
        JSONArray list = rateReady ? state.optJSONArray("stops") : null;
        if (!dragging && list != null && list.length() > 0) {
            int[] next = new int[list.length()];
            for (int i = 0; i < next.length; i++) next[i] = list.optInt(i);
            if (!Arrays.equals(next, stops)) stops = next;
        }
        if (!dragging) {
            int selected = selectedIndex();
            updatingViews = true;
            if (slider.getMax() != stops.length - 1) slider.setMax(stops.length - 1);
            slider.setProgress(selected);
            updatingViews = false;
            bigNumber.setText(String.valueOf(stops[selected]));
        }
        boolean choice = stops.length > 1;
        slider.setVisibility(choice ? View.VISIBLE : View.GONE);
        slider.setEnabled(rateReady && running);
        sliderEnds.setVisibility(choice ? View.VISIBLE : View.GONE);
        float sliderAlpha = connected && !running ? 0.4f : 1f;
        slider.setAlpha(sliderAlpha);
        sliderEnds.setAlpha(sliderAlpha);
        sliderHint.setVisibility(choice && connected && !running ? View.VISIBLE : View.GONE);
        minLabel.setText(stops[0] + " Hz");
        maxLabel.setText(stops[stops.length - 1] + " Hz");
        bigNumber.setTextColor(running ? c.label : c.secondary);

        if (monitor != null) {
            monitorLine.setText(String.format(Locale.US, "%s · %d × %d at %d Hz", monitor.optString("name"),
                    monitor.optInt("width"), monitor.optInt("height"), Math.round(monitor.optDouble("linkHz"))));
        } else {
            monitorLine.setText(connected ? "No monitor connected" : "Not connected");
        }

        if (!busy) {
            updatingViews = true;
            powerSwitch.setChecked(running);
            updatingViews = false;
        }
        powerSwitch.setEnabled(connected && !busy);

        String status;
        boolean alert = false;
        if (message != null) {
            status = message;
            alert = true;
        } else if (!connected) {
            status = "HzUnlock needs Shizuku to change the refresh rate.";
        } else if (state.optBoolean("reconnecting")) {
            status = "Reconnecting the monitor…";
        } else if (state.has("notice")) {
            status = state.optString("notice");
            alert = true;
        } else if (running && state.has("coolingHz") && state.has("appHz")) {
            status = String.format(Locale.US, "Your phone is warm, so apps run at %d Hz until it cools down.",
                    Math.round(state.optDouble("appHz")));
            alert = true;
        } else if (running && monitor != null && state.optBoolean("waiting")) {
            status = "Waiting for the monitor to settle…";
        } else if (running && monitor != null && state.has("appHz")) {
            long app = Math.round(state.optDouble("appHz"));
            long link = Math.round(monitor.optDouble("linkHz"));
            status = String.format(Locale.US, "Apps and animations run at %d Hz.", app);
            if (app > 0 && app < link - 1 && !RefreshSession.smooth(link, app)) {
                status += String.format(Locale.US, " The monitor runs at %d Hz, so motion is a little less even than at "
                        + "a rate that divides it.", link);
            }
        } else if (running) {
            status = "On. Connect a monitor to use it.";
        } else if (state.has("rolledBack")) {
            status = state.optString("rolledBack");
            alert = true;
        } else {
            status = "Off. DeX keeps apps at 60 Hz.";
        }
        statusLine.setText(status);
        statusLine.setTextColor(alert ? c.warn : c.secondary);

        boolean modeControl = caps != null && caps.optBoolean("monitorModes");
        boolean switching = state != null && state.has("modeSwitch");
        String connection = connected && monitor != null && !state.optBoolean("reconnecting") && !switching
                ? connectionAdvice(monitor, modeControl) : null;
        reconnectGroup.setVisibility(connection != null ? View.VISIBLE : View.GONE);
        if (connection != null) {
            // Reconnecting can't reach a mode the monitor lists but didn't start in; it only helps
            // when the faster modes are missing altogether, so it's offered once for that case.
            // After a lost high-refresh connection it's the user's way to try the faster modes again.
            boolean offer = monitor.has("lostHz") || onlyDexRates(monitor) && !monitor.optBoolean("reconnected");
            reconnectButton.setVisibility(running && offer ? View.VISIBLE : View.GONE);
            reconnectText.setText(connection);
        }
        renderMonitorMode(running && modeControl, monitor);
        // Reconnecting brings back DeX's faster modes, so it's part of high refresh being on.
        reconnectRow.setEnabled(running && monitor != null && !busy);
        reconnectRow.setAlpha(reconnectRow.isEnabled() ? 1f : 0.4f);

        renderCompatibility(caps, monitor);
        version.setText("HzUnlock " + BuildConfig.VERSION_NAME);
    }

    /** True when the monitor lists no rate above DeX's standard 60 Hz at any resolution. */
    private static boolean onlyDexRates(JSONObject monitor) {
        long fastest = Math.round(monitor.optDouble("fastestHz"));
        return Math.round(monitor.optDouble("linkHz")) <= 61 && monitor.optInt("maxHz", (int) fastest) <= 61;
    }

    /**
     * Explains the difference between the active mode and the modes Android lists for this
     * connection. With Monitor Mode available, the card below covers faster listed modes.
     */
    private static String connectionAdvice(JSONObject monitor, boolean modeControl) {
        long link = Math.round(monitor.optDouble("linkHz"));
        long fastest = Math.round(monitor.optDouble("fastestHz"));
        String advice = null;
        if (monitor.has("lostHz")) {
            advice = String.format(Locale.US, "The monitor dropped out at %d Hz, so HzUnlock won't retry that by itself. "
                    + "If it happens again, High Refresh turns off. Reconnect Monitor tries again.",
                    Math.round(monitor.optDouble("lostHz")));
        }
        if (monitor.optBoolean("phonePaced")) {
            String paced = monitor.optBoolean("phonePacedRetry")
                    ? "The monitor dropped out when it took over app timing, so the phone screen times apps for a moment. "
                    + "The monitor gets it back once it stays connected."
                    : "The monitor dropped out twice when it took over app timing, so the phone screen times apps for it "
                    + "now. Turn High Refresh off and on to try again.";
            advice = advice == null ? paced : paced + "\n\n" + advice;
        }
        String kept = monitor.optString("keptMode", null);
        String start = monitor.optString("startModeKey", null);
        String active = monitor.optString("modeKey", null);
        boolean chosen = kept != null && kept.equals(active) || start != null && start.equals(active);
        if (!modeControl && monitor.optBoolean("fasterListed") && !chosen) {
            String listed = String.format(Locale.US, "The monitor started at %d Hz but also lists %d Hz. Reconnecting won't "
                    + "change that; a preset in the monitor's menu might. Choosing the monitor's mode needs a Snapdragon "
                    + "phone or root.", link, fastest);
            advice = advice == null ? listed : advice + "\n\n" + listed;
        }
        JSONObject lower = monitor.optJSONObject("lowerRes");
        if (!modeControl && lower != null && !chosen) {
            String hint = String.format(Locale.US, "The monitor also lists %d Hz at %d × %d. DeX's resolution setting "
                            + "can't switch to it; the monitor's menu might.",
                    Math.round(lower.optDouble("hz")), lower.optInt("width"), lower.optInt("height"));
            advice = advice == null ? hint : advice + "\n\n" + hint;
        } else if (advice == null && lower == null && onlyDexRates(monitor)) {
            advice = "Android lists only modes up to 60 Hz on this connection. The hub, cable, or monitor may limit it.";
            if (!monitor.optBoolean("reconnected")) {
                advice += " If you just turned on High Refresh or changed a monitor setting, reconnect once to check.";
            }
        }
        return advice;
    }

    /**
     * The Monitor Mode card, shown while High Refresh is on (root, or a Snapdragon phone): a
     * suggested mode to start with one tap, the other modes, and a running switch with its replug
     * request or "keep this mode?" countdown.
     */
    private void renderMonitorMode(boolean available, JSONObject monitor) {
        JSONObject switching = state == null ? null : state.optJSONObject("modeSwitch");
        boolean show = switching != null || (available && monitor != null);
        modeGroup.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            lastModePhase = null;
            return;
        }
        modePrimary.setVisibility(View.GONE);
        modeChoose.setVisibility(View.GONE);
        modeUndo.setVisibility(View.GONE);
        modeDefault.setVisibility(View.GONE);
        modeTitle.setTextSize(17);
        modeTitle.setTextColor(c.label);
        modeText.setTextColor(c.secondary);
        if (switching != null) {
            String name = switching.optString("short", switching.optString("label"));
            long left = switching.optLong("secondsLeft");
            String phase;
            modeUndo.setText("Undo");
            if (switching.optBoolean("confirm")) {
                phase = "confirm";
                modeTitle.setText("Keep " + name + "?");
                modeText.setText(String.format(Locale.US, "Screen dark? Just wait. It switches back in %d s.", left));
                modePrimary.setText("Keep");
                modePrimary.setOnClickListener(v -> command(s -> s.confirmMonitorMode(true)));
                modePrimary.setVisibility(View.VISIBLE);
            } else if (switching.optBoolean("replug")) {
                phase = "replug";
                modeTitle.setText("Unplug the monitor, then plug it back in");
                modeTitle.setTextSize(20);
                modeTitle.setTextColor(c.warn);
                modeText.setTextColor(c.label);
                modeText.setText(String.format(Locale.US, "It starts at %s when it connects. Waiting %d s.", name, left));
                modeUndo.setText("Cancel");
            } else if (switching.optBoolean("rescue")) {
                phase = "rescue";
                modeTitle.setText("Starting at " + name + "…");
                long lost = switching.optLong("lostHz");
                modeText.setText(lost > 0
                        ? String.format(Locale.US, "The picture dropped at %d Hz. You'll be asked to keep %s.", lost, name)
                        : "You'll be asked to keep it.");
            } else {
                phase = "switching";
                modeTitle.setText("Switching to " + name + "…");
                modeText.setText("The monitor goes dark for a few seconds.");
            }
            modeUndo.setVisibility(View.VISIBLE);
            modeUndo.setEnabled(!busy);
            modePrimary.setEnabled(!busy);
            if (!phase.equals(lastModePhase) && (phase.equals("confirm") || phase.equals("replug"))) {
                // It needs the user now: bring it into view if it's scrolled out of sight.
                screen.post(() -> {
                    int shown = screen.getHeight() - screen.getPaddingTop() - screen.getPaddingBottom();
                    int top = modeGroup.getTop() - screen.getScrollY();
                    if (top < 0 || top + modeGroup.getHeight() > shown) {
                        screen.smoothScrollTo(0, Math.max(0, modeGroup.getTop() - dp(12)));
                    }
                });
                modeGroup.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            }
            lastModePhase = phase;
            return;
        }
        lastModePhase = null;
        modeTitle.setText("Monitor Mode");
        long link = Math.round(monitor.optDouble("linkHz"));
        JSONObject suggest = monitor.optJSONObject("suggest");
        String start = monitor.optString("startMode", null);
        String text;
        if (start != null) {
            text = "Starts at " + start + " each time it connects.";
        } else if (suggest != null && "fast".equals(suggest.optString("reason"))) {
            text = String.format(Locale.US, "%d Hz uses nearly all of the phone's connection. If the picture drops, "
                    + "start the monitor at %s.", link, suggest.optString("label"));
        } else if (suggest != null) {
            text = String.format(Locale.US, "The monitor started at %d Hz but can run at %s.", link, suggest.optString("label"));
        } else {
            text = String.format(Locale.US, "Runs at %d × %d, %d Hz.", monitor.optInt("width"), monitor.optInt("height"), link);
            if (monitor.optBoolean("fasterListed")) {
                text += String.format(Locale.US, " It also lists %d Hz.", Math.round(monitor.optDouble("fastestHz")));
            }
        }
        String result = state.optString("modeResult", null);
        if (result != null) text += "\n\n" + result;
        modeText.setText(text);
        boolean idle = !busy && !state.optBoolean("reconnecting");
        if (suggest != null) {
            modePrimary.setText("Start at " + suggest.optString("label"));
            long displayId = monitor.optLong("id");
            int width = suggest.optInt("width");
            int height = suggest.optInt("height");
            int hz = suggest.optInt("hz");
            modePrimary.setOnClickListener(v -> startMonitorMode(displayId, width, height, hz));
            modePrimary.setVisibility(View.VISIBLE);
            modePrimary.setEnabled(idle);
            modePrimary.setAlpha(idle ? 1f : 0.4f);
        } else {
            modePrimary.setAlpha(1f);
        }
        JSONArray choices = monitor.optJSONArray("modeChoices");
        boolean any = choices != null && choices.length() > 0;
        modeChoose.setText(suggest != null ? "Other Modes…" : "Choose Mode…");
        modeChoose.setVisibility(any ? View.VISIBLE : View.GONE);
        modeChoose.setEnabled(idle);
        modeChoose.setAlpha(idle ? 1f : 0.4f);
        if (start != null) {
            modeDefault.setVisibility(View.VISIBLE);
            modeDefault.setEnabled(!busy);
        }
    }

    /**
     * The monitor's useful modes, grouped by size (the current size first) with the fastest
     * first. A tap starts the switch; the card then asks to keep it.
     */
    private void chooseMonitorMode() {
        JSONObject monitor = state == null ? null : state.optJSONObject("monitor");
        JSONArray choices = monitor == null ? null : monitor.optJSONArray("modeChoices");
        if (choices == null || choices.length() == 0 || dialogShowing()) return;
        long displayId = monitor.optLong("id");
        int currentWidth = monitor.optInt("width");
        int currentHeight = monitor.optInt("height");
        JSONObject suggest = monitor.optJSONObject("suggest");
        Map<String, List<JSONObject>> bySize = new LinkedHashMap<>();
        for (int i = 0; i < choices.length(); i++) {
            JSONObject choice = choices.optJSONObject(i);
            bySize.computeIfAbsent(choice.optInt("width") + " × " + choice.optInt("height"), k -> new ArrayList<>()).add(choice);
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(24), dp(4), dp(24), dp(8));
        scroll.addView(list);
        label(list, "The monitor goes dark for a few seconds. Then tap Keep.", 14, c.secondary, Typeface.NORMAL);
        for (Map.Entry<String, List<JSONObject>> group : bySize.entrySet()) {
            JSONObject first = group.getValue().get(0);
            boolean current = first.optInt("width") == currentWidth && first.optInt("height") == currentHeight;
            TextView header = label(list, group.getKey() + (current ? " (now)" : ""), 13, c.secondary, Typeface.BOLD);
            top(header, 16);
            group.getValue().sort((a, b) -> Integer.compare(b.optInt("hz"), a.optInt("hz")));
            for (JSONObject choice : group.getValue()) {
                int width = choice.optInt("width");
                int height = choice.optInt("height");
                int hz = choice.optInt("hz");
                boolean recommended = suggest != null && suggest.optInt("width") == width
                        && suggest.optInt("height") == height && suggest.optInt("hz") == hz;
                TextView row = label(list, hz + " Hz" + (recommended ? "  · Recommended" : ""), 17,
                        recommended ? c.accent : c.label, Typeface.NORMAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinHeight(dp(48));
                row.setPadding(dp(4), 0, dp(4), 0);
                row.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(40, 128, 128, 128)), null,
                        new ColorDrawable(Color.WHITE)));
                row.setOnClickListener(v -> {
                    if (dialog != null) dialog.dismiss();
                    startMonitorMode(displayId, width, height, hz);
                });
            }
        }
        dialog = dialogBuilder()
                .setTitle("Monitor Mode")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Starts the switch at once. Nothing is lost by a wrong pick: unless the user taps Keep within
     * 20 s, High Refresh turns off and DeX goes back to its standard modes.
     */
    private void startMonitorMode(long displayId, int width, int height, int hz) {
        command(s -> s.switchMonitorMode(displayId, width, height, hz));
    }

    private void renderCompatibility(JSONObject caps, JSONObject monitor) {
        if (caps == null) {
            compatibilityValue.setText("");
            compatibilityList.removeAllViews();
            return;
        }
        boolean highRes = caps.optBoolean("highRes");
        boolean monitorTiming = caps.optBoolean("monitorTiming");
        boolean phoneRates = caps.optBoolean("phoneRates");
        boolean heat = caps.optBoolean("heat");
        compatibilityValue.setText(monitorTiming && phoneRates ? "Full" : monitorTiming || phoneRates ? "Partial" : "Limited");

        String full = monitor != null ? Math.round(monitor.optDouble("linkHz")) + " Hz" : "the monitor's rate";
        String noTiming = "none".equals(caps.optString("codes"))
                ? "This One UI version doesn't have Samsung's call for moving app timing to the monitor, so apps top out at the phone screen's rate."
                : "This phone didn't accept moving app timing to the monitor, so apps top out at the phone screen's rate.";
        Object[][] rows = {
                {highRes, "High-refresh modes", highRes ? "DeX can use the monitor's fast modes." : "DeX's mode unlock isn't available."},
                {monitorTiming, "Full monitor rate", monitorTiming ? "Apps can run at " + full + "." : noTiming},
                {phoneRates, "Lower rates", phoneRates ? "The slider can pace apps from the phone screen." : "Rate control isn't available."},
                {heat, "Heat protection", heat ? "HzUnlock can see the phone's temperature." : "Temperature isn't readable."},
        };
        compatibilityList.removeAllViews();
        for (int i = 0; i < rows.length; i++) {
            boolean ok = (Boolean) rows[i][0];
            if (i > 0) {
                View line = new View(this);
                line.setBackgroundColor(c.separator);
                compatibilityList.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2)));
            }
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(10), 0, dp(10));
            row.setGravity(Gravity.CENTER_VERTICAL);
            compatibilityList.addView(row, new LinearLayout.LayoutParams(-1, -2));
            TextView mark = label(row, ok ? "✓" : "–", 17, ok ? c.accent : c.secondary, Typeface.BOLD);
            mark.setLayoutParams(new LinearLayout.LayoutParams(dp(28), -2));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            label(labels, (String) rows[i][1], 16, c.label, Typeface.NORMAL);
            label(labels, (String) rows[i][2], 13, c.secondary, Typeface.NORMAL);
        }
        TextView device = label(compatibilityList, state.optString("device"), 12, c.secondary, Typeface.NORMAL);
        device.setPadding(0, dp(10), 0, 0);
    }

    /** The slider applies a rate only while High Refresh runs; off, DeX's standard modes leave nothing to set. */
    private boolean canSetRate() {
        return rateOptionsReady() && state.optBoolean("running");
    }

    private boolean rateOptionsReady() {
        return state != null && state.has("monitor") && !state.optBoolean("reconnecting")
                && !state.optBoolean("waiting") && !state.has("modeSwitch");
    }

    private int selectedIndex() {
        float target = prefs.target();
        if (target <= 0) return stops.length - 1;
        int best = 0;
        for (int i = 0; i < stops.length; i++) {
            if (Math.abs(stops[i] - target) < Math.abs(stops[best] - target)) best = i;
        }
        return best;
    }

    private void commitRate(int hz) {
        prefs.setTarget(hz >= stops[stops.length - 1] ? Prefs.FASTEST : hz);
        pushSettings();
        render();
    }

    // ---- actions -----------------------------------------------------------------------------

    private void toggleCompatibility() {
        boolean show = compatibilityGroup.getVisibility() != View.VISIBLE;
        compatibilityGroup.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * Report an Issue: collects the full report (as Diagnostics shows it), saves it to Downloads,
     * copies it, and opens a new GitHub issue that says which file to attach. Without Shizuku the
     * report has only what the app knows.
     */
    private void reportIssue() {
        IDisplayService service = bridge.service();
        boolean includeLog = prefs.includeSystemLog();
        Toast.makeText(this, "Preparing the report…", Toast.LENGTH_SHORT).show();
        commands.execute(() -> {
            JSONObject current = null;
            JSONObject diagnostics = null;
            if (service != null) {
                try {
                    current = new JSONObject(service.getState());
                    String raw = service.getDiagnostics(includeLog);
                    if (raw != null) diagnostics = new JSONObject(raw);
                } catch (Exception ignored) {
                    // The report then has what the app knows.
                }
            }
            String report = Report.full(Report.summary(current, prefs), diagnostics, HzUnlockApp.savedCrash(this));
            IssueReporter.Saved saved = null;
            try {
                saved = IssueReporter.save(this, report);
            } catch (Exception ignored) {
                // Falls back to the clipboard below.
            }
            String file = saved == null ? null : saved.name();
            String url = IssueReporter.issueUrl(current, diagnostics, prefs, file);
            main.post(() -> {
                IssueReporter.copy(this, report);
                Toast.makeText(this, file != null
                        ? "Report saved to Downloads as " + file + ". Attach it on the GitHub page."
                        : "Report copied. Paste it on the GitHub page.", Toast.LENGTH_LONG).show();
                IssueReporter.open(this, url);
            });
        });
    }

    private void openDiagnostics() {
        startActivity(new Intent(this, DiagnosticsActivity.class));
    }

    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(this, c.dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    private boolean dialogShowing() {
        return dialog != null && dialog.isShowing();
    }

    /** Offers to report a crash of the app or its display service that the user hasn't seen yet. */
    private void alertCrash() {
        if (!visible || dialogShowing()) return;
        long app = HzUnlockApp.savedCrashTime(this);
        long service = state == null ? 0 : state.optLong("crashAt");
        long newest = Math.max(app, service);
        if (newest <= prefs.seenCrash()) return;
        boolean serviceCrash = service >= app;
        dialog = dialogBuilder()
                .setTitle(serviceCrash ? "HzUnlock's Display Service Stopped" : "HzUnlock Closed Unexpectedly")
                .setMessage("A report helps get this fixed. You'll see everything in it before anything is shared.")
                .setPositiveButton("Review Report", (d, which) -> {
                    prefs.setSeenCrash(newest);
                    openDiagnostics();
                })
                .setNegativeButton("Not Now", (d, which) -> prefs.setSeenCrash(newest))
                .setOnCancelListener(d -> prefs.setSeenCrash(newest))
                .setOnDismissListener(d -> showPendingUpdate())
                .show();
    }

    /**
     * Asks GitHub whether a newer release is out. Automatic checks run at most once a day when
     * Check for Updates is on, and announce only checkpoint releases (notes marked
     * {@code <!-- notify -->}) newer than any version the user skipped, so small updates don't
     * nag. A manual check always runs and reports any newer release.
     */
    private void checkForUpdate(boolean manual) {
        long now = System.currentTimeMillis();
        if (checkingUpdate) return;
        if (!manual && (!prefs.updateCheck() || now - prefs.lastUpdateCheck() < UpdateChecker.INTERVAL_MS)) return;
        checkingUpdate = true;
        prefs.setLastUpdateCheck(now);
        if (manual) Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show();
        network.execute(() -> {
            UpdateChecker.Update release = null;
            String error = null;
            try {
                release = UpdateChecker.newerThan(BuildConfig.VERSION_NAME);
            } catch (Exception e) {
                error = Errors.describe(e);
            }
            UpdateChecker.Update found = release;
            String problem = error;
            main.post(() -> {
                checkingUpdate = false;
                if (found != null && (manual || announce(found))) {
                    pendingUpdate = found;
                    showPendingUpdate();
                } else if (manual && !isDestroyed()) {
                    Toast.makeText(this, problem != null ? "Couldn't check for updates. " + problem
                            : "HzUnlock " + BuildConfig.VERSION_NAME + " is up to date.", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** Whether an automatic check should show {@code update}: only for a checkpoint not skipped. */
    private boolean announce(UpdateChecker.Update update) {
        String skipped = prefs.skippedVersion();
        return update.checkpoint() != null
                && (skipped.isEmpty() || Versions.compare(update.checkpoint(), skipped) > 0);
    }

    private void showPendingUpdate() {
        UpdateChecker.Update release = pendingUpdate;
        if (release == null || !visible || dialogShowing()) return;
        pendingUpdate = null;
        String notes = release.notes().isEmpty() ? "" : release.notes() + "\n\n";
        dialog = dialogBuilder()
                .setTitle("HzUnlock " + release.version() + " Is Available")
                .setMessage(notes + "You have " + BuildConfig.VERSION_NAME + ". Download the new version from GitHub "
                        + "and install it over this one. Your settings carry over.")
                .setPositiveButton("Download", (d, which) -> openUri(release.pageUrl()))
                .setNegativeButton("Later", null)
                .setNeutralButton("Skip This Version", (d, which) -> prefs.setSkippedVersion(release.version()))
                .show();
    }

    private void addTile() {
        StatusBarManager bar = getSystemService(StatusBarManager.class);
        bar.requestAddTileService(new ComponentName(this, HzTileService.class), getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_tile), getMainExecutor(), result -> {
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                        Toast.makeText(this, "The tile is already in Quick Settings.", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void showSetup(String title, String body, String action, Runnable click) {
        setupGroup.setVisibility(View.VISIBLE);
        setupTitle.setText(title);
        setupBody.setText(body);
        setupButton.setText(action);
        setupAction = click;
    }

    private void openShizuku() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(ServiceBridge.SHIZUKU_PACKAGE);
        if (launch != null) startActivity(launch);
    }

    private void openUri(String uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        } catch (RuntimeException e) {
            message = "No browser is available.";
            render();
        }
    }

    @FunctionalInterface
    private interface Command {
        String run(IDisplayService service) throws android.os.RemoteException;
    }
}
