package io.github.npfp222.hzunlock;

interface IDisplayService {
    int getServiceUid() = 0;
    /** JSON snapshot of the session, the monitor, and the selectable rates. */
    String getState() = 1;
    String startBoost(float targetHz, boolean autoReconnect, boolean coolDown) = 2;
    String stopBoost() = 3;
    /** Applies new settings to a running session, or stores them for the next start. */
    void configure(float targetHz, boolean autoReconnect, boolean coolDown) = 4;
    /** Renegotiates the DisplayPort link so the monitor can come up in its fastest timing. */
    String reconnectMonitor() = 5;
    /**
     * JSON with the session's event log, the last saved service crash, and (when asked) the
     * display-related part of the system log.
     */
    String getDiagnostics(boolean includeSystemLog) = 6;
    /** Deletes the saved service crash once it has been reported or dismissed. */
    void clearCrash() = 7;
    oneway void destroy() = 16777114;
}
