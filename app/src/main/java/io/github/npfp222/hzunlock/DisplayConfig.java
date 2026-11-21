package io.github.npfp222.hzunlock;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

/**
 * Qualcomm's display config HAL ({@code vendor.qti.hardware.display.config.IDisplayConfig/default}),
 * which the ADB shell user may call. It reads and sets the external display's mode in the
 * composer directly. SurfaceFlinger isn't told about such a switch, except when it happens before
 * SurfaceFlinger first reads the display at hotplug (see {@link StartModeRacer}).
 *
 * <p>Transaction codes are the stable AIDL method order: 1 isDisplayConnected, 4 getConfigCount,
 * 5 getActiveConfig, 6 setActiveConfig, 7 getDisplayAttributes. Snapdragon only.
 */
final class DisplayConfig {
    static final String SERVICE = "vendor.qti.hardware.display.config.IDisplayConfig/default";
    /** DisplayType EXTERNAL: the DisplayPort monitor. */
    static final int EXTERNAL = 2;
    private static final int IS_CONNECTED = 1;
    private static final int CONFIG_COUNT = 4;
    private static final int ACTIVE = 5;
    private static final int SET_ACTIVE = 6;
    private static final int ATTRIBUTES = 7;
    /** Error marker for a call the service answered with a failure status (e.g. no monitor). */
    static final int FAILED = Integer.MIN_VALUE;

    /** One composer mode of the monitor. */
    record Config(int index, int width, int height, int vsyncNs) {
        float hz() {
            return 1e9f / vsyncNs;
        }

        int roundHz() {
            return Math.round(hz());
        }

        boolean is(int w, int h, int rate) {
            return width == w && height == h && roundHz() == rate;
        }

        String key() {
            return width + "x" + height + "@" + roundHz();
        }
    }

    private final IBinder binder;
    private final String descriptor;

    private DisplayConfig(IBinder binder, String descriptor) {
        this.binder = binder;
        this.descriptor = descriptor;
    }

    /** The service, or null on phones without it (Exynos, Tensor, other vendors). */
    static DisplayConfig open() {
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("checkService", String.class).invoke(null, SERVICE);
            if (binder == null) return null;
            DisplayConfig config = new DisplayConfig(binder, binder.getInterfaceDescriptor());
            // A plain query that every version answers; a refusal (SELinux) leaves the feature off.
            config.connected();
            return config;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** True while the composer has a monitor. Unlike {@link #active()}, it doesn't log when there's none. */
    boolean connected() throws RemoteException {
        return call(IS_CONNECTED, EXTERNAL) == 1;
    }

    int count() throws RemoteException {
        return call(CONFIG_COUNT, EXTERNAL);
    }

    /** The active config index, or {@link #FAILED} when there's no monitor. */
    int active() throws RemoteException {
        return call(ACTIVE, EXTERNAL);
    }

    boolean set(int index) throws RemoteException {
        return call(SET_ACTIVE, EXTERNAL, index) != FAILED;
    }

    /** The config at {@code index}, or null when it can't be read or looks implausible. */
    Config config(int index) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeInt(index);
            data.writeInt(EXTERNAL);
            if (!binder.transact(ATTRIBUTES, data, reply, 0)) return null;
            if (reply.readInt() != 0) return null; // status
            if (reply.readInt() == 0) return null; // null parcelable
            reply.readInt(); // parcelable size
            int vsync = reply.readInt();
            int width = reply.readInt();
            int height = reply.readInt();
            boolean plausible = vsync >= 2_000_000 && vsync <= 100_000_000
                    && width >= 320 && width <= 16384 && height >= 200 && height <= 16384;
            return plausible ? new Config(index, width, height, vsync) : null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Every readable config of the monitor connected now; empty without one. */
    List<Config> configs() throws RemoteException {
        List<Config> out = new ArrayList<>();
        int count = count();
        if (count <= 0 || count > 512) return out;
        for (int i = 0; i < count; i++) {
            Config config = config(i);
            if (config != null) out.add(config);
        }
        return out;
    }

    /** The monitor's fastest mode by rate, larger first on a tie: a fingerprint of the monitor and its preset. */
    static String fastestKey(List<Config> configs) {
        Config best = null;
        for (Config config : configs) {
            if (best == null || config.roundHz() > best.roundHz() || config.roundHz() == best.roundHz()
                    && (long) config.width() * config.height() > (long) best.width() * best.height()) {
                best = config;
            }
        }
        return best == null ? "" : best.key();
    }

    private int call(int code, int... args) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            for (int arg : args) data.writeInt(arg);
            if (!binder.transact(code, data, reply, 0)) return FAILED;
            if (reply.readInt() != 0) return FAILED;
            return reply.dataAvail() >= 4 ? reply.readInt() : 0;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
