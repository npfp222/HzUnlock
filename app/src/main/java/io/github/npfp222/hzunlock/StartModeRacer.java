package io.github.npfp222.hzunlock;

import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Starts the monitor in a chosen mode without root.
 *
 * <p>When a monitor connects, Qualcomm's composer creates the display and only then tells
 * SurfaceFlinger, which reads the display's active mode once, at that hotplug. Measured on a
 * Galaxy S24 Ultra, the gap is 6–17 ms. A {@link DisplayConfig#set} inside it changes the mode
 * the monitor is first driven in, and SurfaceFlinger takes it as the display's own mode, so app
 * timing matches. Later switches would leave SurfaceFlinger timing the old mode.
 *
 * <p>While armed, a thread polls the composer for a new connection (every millisecond when one
 * is likely, more slowly otherwise) and, on each one whose mode list fits the target, sets the
 * target mode. Whether it won is up to the caller: SurfaceFlinger's mode for the new display
 * either equals the target or doesn't.
 */
final class StartModeRacer {
    /** The mode to start in, for the monitor whose fastest mode is {@code fastestKey}. */
    record Target(int width, int height, int hz, String fastestKey) {
        String key() {
            return width + ":" + height + ":" + hz;
        }

        String label() {
            return width + " × " + height + " at " + hz + " Hz";
        }

        String serialize() {
            return width + ":" + height + ":" + hz + ":" + fastestKey;
        }

        static Target parse(String text) {
            try {
                String[] parts = text.strip().split(":", 4);
                if (parts.length != 4 || parts[3].isEmpty()) return null;
                return new Target(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), parts[3]);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    enum Outcome {
        /** The target was set before SurfaceFlinger's read, as far as the racer can tell. */
        SET,
        /** The monitor already started in the target mode. */
        ALREADY,
        /** Another monitor, another preset of it, or DeX's standard modes: left alone. */
        OTHER,
        /** The composer refused the switch. */
        REFUSED
    }

    /**
     * One connection the racer acted on. {@code previousIndex} is the mode the composer started
     * in, so a lost race can be put back; {@code micros} is the time from seeing the connection
     * to the switch call returning.
     */
    record Attempt(Target target, long at, Outcome outcome, int index, int previousIndex, long micros) {}

    /** Poll this fast while a connection is likely: a cable is attached or one just went away. */
    private static final long FAST_NS = 1_000_000L;
    private static final long SLOW_NS = 25_000_000L;
    /** Without readable USB port state: still inside the shortest measured gap (6 ms), at a quarter of the cost. */
    private static final long UNKNOWN_NS = 4_000_000L;
    /** While a monitor is connected there's nothing to race; wait for it to go. */
    private static final long CONNECTED_NS = 50_000_000L;
    private static final long IDLE_NS = 500_000_000L;
    /** After a monitor goes away, stay fast this long for DeX or the driver re-adding it. */
    static final long AFTER_DISCONNECT_MS = 30_000;
    private static final long USB_CHECK_MS = 250;

    private final DisplayConfig config;
    private volatile Target target;
    private volatile long fastUntil;
    private volatile Attempt last;
    private final AtomicInteger matches = new AtomicInteger();
    private Thread thread;

    /** USB port state through IUsbManager (shell holds MANAGE_USB); null when unreadable. */
    private Object usb;
    private boolean usbUnreadable;
    private long usbCheckedAt;
    private boolean usbAttached;

    StartModeRacer(DisplayConfig config) {
        this.config = config;
    }

    /** Arms the racer for {@code target}; polls fast for {@code fastMs} whatever the cable state. */
    synchronized void arm(Target target, long fastMs) {
        this.target = target;
        fastUntil = Math.max(fastUntil, SystemClock.elapsedRealtime() + fastMs);
        if (thread == null) {
            thread = new Thread(this::run, "hzunlock-start-mode");
            thread.setDaemon(true);
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        } else {
            LockSupport.unpark(thread);
        }
    }

    void disarm() {
        target = null;
    }

    Target target() {
        return target;
    }

    Attempt last() {
        return last;
    }

    /** How many connections the target was set (or already active) on so far. */
    int matches() {
        return matches.get();
    }

    synchronized void close() {
        target = null;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void run() {
        boolean wasConnected = true;
        boolean primed = false;
        while (!Thread.currentThread().isInterrupted()) {
            Target armed = target;
            if (armed == null) {
                primed = false;
                LockSupport.parkNanos(IDLE_NS);
                continue;
            }
            try {
                boolean connected = config.connected();
                // A monitor already connected when the racer is armed belongs to an earlier
                // connection; only a new one may be switched.
                if (!primed) {
                    primed = true;
                    wasConnected = connected;
                }
                if (connected && !wasConnected) attempt(armed);
                if (!connected && wasConnected) {
                    fastUntil = Math.max(fastUntil, SystemClock.elapsedRealtime() + AFTER_DISCONNECT_MS);
                }
                wasConnected = connected;
                LockSupport.parkNanos(connected ? CONNECTED_NS : likely() ? FAST_NS : usbUnreadable ? UNKNOWN_NS : SLOW_NS);
            } catch (RemoteException | RuntimeException e) {
                // The composer restarted or refused; try again shortly.
                LockSupport.parkNanos(IDLE_NS);
            }
        }
    }

    private void attempt(Target armed) throws RemoteException {
        long seen = SystemClock.elapsedRealtimeNanos();
        List<DisplayConfig.Config> configs = config.configs();
        DisplayConfig.Config hit = null;
        for (DisplayConfig.Config c : configs) {
            if (c.is(armed.width(), armed.height(), armed.hz())) {
                hit = c;
                break;
            }
        }
        int previous = config.active();
        Outcome outcome;
        if (hit == null || !DisplayConfig.fastestKey(configs).equals(armed.fastestKey())) {
            outcome = Outcome.OTHER;
        } else if (previous == hit.index()) {
            outcome = Outcome.ALREADY;
        } else {
            outcome = config.set(hit.index()) ? Outcome.SET : Outcome.REFUSED;
        }
        long micros = (SystemClock.elapsedRealtimeNanos() - seen) / 1_000;
        last = new Attempt(armed, SystemClock.elapsedRealtime(), outcome, hit == null ? -1 : hit.index(), previous, micros);
        if (outcome == Outcome.SET || outcome == Outcome.ALREADY) matches.incrementAndGet();
    }

    /** True when a connection may come soon: just armed or disconnected, or a USB-C partner attached. */
    private boolean likely() {
        long now = SystemClock.elapsedRealtime();
        if (now < fastUntil) return true;
        if (now - usbCheckedAt >= USB_CHECK_MS) {
            usbCheckedAt = now;
            usbAttached = usbPartner();
        }
        return usbAttached;
    }

    /** True when any USB port reports a partner; false when none does or the ports can't be read. */
    private boolean usbPartner() {
        if (usbUnreadable) return false;
        try {
            if (usb == null) {
                IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class).invoke(null, "usb");
                usb = Class.forName("android.hardware.usb.IUsbManager$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, binder);
            }
            List<?> ports = (List<?>) usb.getClass().getMethod("getPorts").invoke(usb);
            if (ports == null) return false;
            for (Object port : ports) {
                String id = (String) port.getClass().getMethod("getId").invoke(port);
                Object status = usb.getClass().getMethod("getPortStatus", String.class).invoke(usb, id);
                if (status != null && (Boolean) status.getClass().getMethod("isConnected").invoke(status)) return true;
            }
            return false;
        } catch (Exception | LinkageError e) {
            usbUnreadable = true;
            return false;
        }
    }
}
