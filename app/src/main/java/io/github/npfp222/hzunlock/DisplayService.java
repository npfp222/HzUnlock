package io.github.npfp222.hzunlock;

import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.system.Os;

import org.json.JSONObject;

/** Privileged implementation that Shizuku loads as ADB shell or root, separate from the app process. */
public final class DisplayService extends IDisplayService.Stub {
    static final int ROOT_UID = 0;
    static final int SHELL_UID = 2000;

    private final int ownerUid;
    private final int serviceUid = Os.getuid();
    private final RefreshSession session;
    private final String device;

    public DisplayService(Context context) {
        ownerUid = context.getApplicationInfo().uid;
        DisplayControl.init(context);
        session = new RefreshSession(serviceUid == ROOT_UID);
        device = describeDevice();
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
            return session.stop();
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
    public void destroy() {
        int caller = Binder.getCallingUid();
        if (caller != serviceUid && caller != ownerUid) {
            throw new SecurityException("This caller cannot stop the display service.");
        }
        Binder.clearCallingIdentity();
        try {
            session.stop();
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
