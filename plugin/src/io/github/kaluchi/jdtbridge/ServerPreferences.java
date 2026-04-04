package io.github.kaluchi.jdtbridge;

import java.net.InetAddress;

import org.eclipse.core.runtime.Platform;

/**
 * HTTP server preference keys and resolution.
 * Keys are shared between plugin (reader) and UI (writer).
 * Preferences stored per-workspace via InstanceScope.
 */
public class ServerPreferences {

    /** Preference node ID — UI bundle writes, plugin reads. */
    public static final String PREFERENCE_NODE =
            "io.github.kaluchi.jdtbridge.ui";

    /** "loopback" (default) or "all" */
    public static final String HTTP_BIND_ADDRESS = "httpBindAddress";

    /** 0 = auto-assigned (default), 1024-65535 = fixed */
    public static final String HTTP_FIXED_PORT = "httpFixedPort";

    public static final String BIND_LOOPBACK = "loopback";
    public static final String BIND_ALL = "all";

    /** Read bind address from workspace preferences. */
    public static InetAddress resolveBindAddress() {
        try {
            String bindAddressMode = Platform.getPreferencesService()
                    .getString(PREFERENCE_NODE, HTTP_BIND_ADDRESS,
                            BIND_LOOPBACK, null);
            if (BIND_ALL.equals(bindAddressMode)) {
                return InetAddress.getByName("0.0.0.0");
            }
        } catch (Exception e) {
            Log.warn("Failed to read bind address preference", e);
        }
        return InetAddress.getLoopbackAddress();
    }

    /** Read fixed port from workspace preferences. */
    public static int resolveFixedPort() {
        try {
            return Platform.getPreferencesService().getInt(
                    PREFERENCE_NODE, HTTP_FIXED_PORT, 0, null);
        } catch (Exception e) {
            Log.warn("Failed to read port preference", e);
            return 0;
        }
    }
}
