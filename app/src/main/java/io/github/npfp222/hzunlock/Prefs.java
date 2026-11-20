package io.github.npfp222.hzunlock;

import android.content.Context;
import android.content.SharedPreferences;

/** The user's saved choices, shared by the app screen, the Quick Settings tile, and shortcuts. */
final class Prefs {
    /** Target of 0 means "the fastest rate the monitor offers". */
    static final float FASTEST = 0f;

    private final SharedPreferences store;

    Prefs(Context context) {
        store = context.getSharedPreferences("hzunlock", Context.MODE_PRIVATE);
    }

    float target() {
        return store.getFloat("target", FASTEST);
    }

    /** What the service should aim for: a real rate, or effectively unlimited for "fastest". */
    float serviceTarget() {
        float target = target();
        return target <= 0 ? 1000f : target;
    }

    void setTarget(float hz) {
        store.edit().putFloat("target", hz).apply();
    }


    boolean restore() {
        return store.getBoolean("restore", true);
    }

    void setRestore(boolean value) {
        store.edit().putBoolean("restore", value).apply();
    }

    boolean autoReconnect() {
        return store.getBoolean("autoReconnect", false);
    }

    void setAutoReconnect(boolean value) {
        store.edit().putBoolean("autoReconnect", value).apply();
    }

    boolean coolDown() {
        return store.getBoolean("coolDown", false);
    }

    void setCoolDown(boolean value) {
        store.edit().putBoolean("coolDown", value).apply();
    }

    /** Check GitHub for a newer release about once a day. */
    boolean updateCheck() {
        return store.getBoolean("updateCheck", true);
    }

    void setUpdateCheck(boolean value) {
        store.edit().putBoolean("updateCheck", value).apply();
    }

    long lastUpdateCheck() {
        return store.getLong("lastUpdateCheck", 0);
    }

    void setLastUpdateCheck(long time) {
        store.edit().putLong("lastUpdateCheck", time).apply();
    }

    /** The newest release when the user chose Skip This Version; only a later checkpoint pops up again. */
    String skippedVersion() {
        return store.getString("skippedVersion", "");
    }

    void setSkippedVersion(String version) {
        store.edit().putString("skippedVersion", version).apply();
    }

    /** The newest app or service crash time the user has already been told about. */
    long seenCrash() {
        return store.getLong("seenCrash", 0);
    }

    void setSeenCrash(long time) {
        if (time > seenCrash()) store.edit().putLong("seenCrash", time).apply();
    }

    /** Whether diagnostics reports include the display-related system log. */
    boolean includeSystemLog() {
        return store.getBoolean("includeSystemLog", true);
    }

    void setIncludeSystemLog(boolean value) {
        store.edit().putBoolean("includeSystemLog", value).apply();
    }

    /** Whether the user last left high refresh on. */
    boolean wantOn() {
        return store.getBoolean("wantOn", false);
    }

    void setWantOn(boolean value) {
        store.edit().putBoolean("wantOn", value).apply();
    }

    String start(IDisplayService service) throws android.os.RemoteException {
        setWantOn(true);
        return service.startBoost(serviceTarget(), autoReconnect(), coolDown());
    }

    String stop(IDisplayService service) throws android.os.RemoteException {
        setWantOn(false);
        return service.stopBoost();
    }

    void push(IDisplayService service) throws android.os.RemoteException {
        service.configure(serviceTarget(), autoReconnect(), coolDown());
    }

}
