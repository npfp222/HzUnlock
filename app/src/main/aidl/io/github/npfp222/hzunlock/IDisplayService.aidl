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
    oneway void destroy() = 16777114;
}
