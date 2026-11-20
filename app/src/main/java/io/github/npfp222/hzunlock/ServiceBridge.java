package io.github.npfp222.hzunlock;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import rikka.shizuku.Shizuku;

/** Binds HzUnlock's privileged display service through Shizuku. Call from the main thread. */
final class ServiceBridge {
    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    enum Access { NOT_INSTALLED, NOT_RUNNING, OUTDATED, NO_PERMISSION, READY }

    interface Listener {
        void onConnected(IDisplayService service);

        void onDisconnected();
    }

    private final Context context;
    private final Listener listener;
    private final Shizuku.UserServiceArgs args;
    private IDisplayService service;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!bound) return;
            service = IDisplayService.Stub.asInterface(binder);
            listener.onConnected(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            listener.onDisconnected();
        }
    };

    ServiceBridge(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        args = new Shizuku.UserServiceArgs(new ComponentName(this.context, DisplayService.class))
                .processNameSuffix("displays").tag("hzunlock-displays")
                .version(BuildConfig.VERSION_CODE).daemon(true);
    }

    static Access access(Context context) {
        try {
            if (!Shizuku.pingBinder()) return installed(context) ? Access.NOT_RUNNING : Access.NOT_INSTALLED;
            if (Shizuku.getVersion() < 13) return Access.OUTDATED;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return Access.NO_PERMISSION;
            return Access.READY;
        } catch (RuntimeException e) {
            return Access.NOT_RUNNING;
        }
    }

    private static boolean installed(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    IDisplayService service() {
        return service;
    }

    boolean binding() {
        return bound && service == null;
    }

    /** Binds when Shizuku is ready; returns false otherwise. */
    boolean connect() {
        if (bound) return true;
        if (access(context) != Access.READY) return false;
        bound = true;
        try {
            Shizuku.bindUserService(args, connection);
            return true;
        } catch (RuntimeException e) {
            bound = false;
            return false;
        }
    }

    /** Unbinds without stopping the service; the session keeps running inside Shizuku. */
    void release() {
        boolean wasBound = bound;
        bound = false;
        service = null;
        if (!wasBound) return;
        try {
            if (Shizuku.pingBinder()) Shizuku.unbindUserService(args, connection, false);
        } catch (RuntimeException ignored) {
            // Shizuku may have gone away between the ping and the unbind.
        }
    }
}
