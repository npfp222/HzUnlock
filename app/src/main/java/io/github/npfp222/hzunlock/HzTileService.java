package io.github.npfp222.hzunlock;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/** Quick Settings toggle for high refresh. Long-press opens HzUnlock. */
public final class HzTileService extends TileService implements ServiceBridge.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private ServiceBridge bridge;
    private Prefs prefs;
    private boolean listening;
    private boolean clickPending;
    private boolean restored;
    private Boolean running;
    private String rate;

    private final Shizuku.OnBinderReceivedListener received = () -> main.post(this::tryConnect);

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        bridge = new ServiceBridge(this, this);
    }

    @Override
    public void onStartListening() {
        listening = true;
        Shizuku.addBinderReceivedListenerSticky(received);
        tryConnect();
        updateTile();
    }

    @Override
    public void onStopListening() {
        listening = false;
        Shizuku.removeBinderReceivedListener(received);
        bridge.release();
        restored = false;
    }

    @Override
    public void onDestroy() {
        work.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onClick() {
        IDisplayService service = bridge.service();
        if (service != null) {
            toggle(service);
        } else if (ServiceBridge.access(this) == ServiceBridge.Access.READY) {
            clickPending = true;
            bridge.connect();
        } else {
            openApp();
        }
    }

    private void tryConnect() {
        if (!listening) return;
        if (!bridge.connect()) {
            running = null;
            updateTile();
        }
    }

    @Override
    public void onConnected(IDisplayService service) {
        work.execute(() -> {
            try {
                prefs.push(service);
                JSONObject state = new JSONObject(service.getState());
                // A session that turned itself off to undo a monitor mode switch stays off.
                if (state.has("rolledBack")) prefs.setWantOn(false);
                if (!restored && !clickPending && prefs.restore() && prefs.wantOn() && !state.optBoolean("running")) {
                    prefs.start(service);
                    state = new JSONObject(service.getState());
                }
                restored = true;
                show(state);
                if (clickPending) {
                    clickPending = false;
                    main.post(() -> toggle(service));
                }
            } catch (Exception e) {
                main.post(this::onDisconnected);
            }
        });
    }

    @Override
    public void onDisconnected() {
        bridge.release();
        running = null;
        updateTile();
    }

    private void toggle(IDisplayService service) {
        boolean turnOn = !Boolean.TRUE.equals(running);
        running = turnOn;
        updateTile();
        work.execute(() -> {
            try {
                if (turnOn) prefs.start(service);
                else prefs.stop(service);
                show(new JSONObject(service.getState()));
            } catch (Exception e) {
                main.post(this::onDisconnected);
            }
        });
    }

    private void show(JSONObject state) {
        boolean on = state.optBoolean("running");
        String label = on && state.has("appHz") ? Math.round(state.optDouble("appHz")) + " Hz" : null;
        main.post(() -> {
            running = on;
            rate = label;
            updateTile();
        });
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_tile));
        tile.setLabel("High refresh");
        if (running == null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle(ServiceBridge.access(this) == ServiceBridge.Access.READY ? "Connecting…" : "Start Shizuku");
        } else if (running) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setSubtitle(rate != null ? rate : "On");
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle("Off");
        }
        tile.updateTile();
    }

    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
    }
}
