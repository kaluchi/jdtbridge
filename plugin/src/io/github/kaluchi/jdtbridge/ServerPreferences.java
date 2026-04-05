package io.github.kaluchi.jdtbridge;

import org.eclipse.core.runtime.Platform;

/**
 * HTTP server preference keys and resolution.
 * Keys are shared between plugin (reader) and UI (writer).
 * Preferences stored per-workspace via InstanceScope.
 *
 * Two sockets:
 * - Local: loopback only, auto-generated token by default
 * - Remote: all interfaces (0.0.0.0), fixed token, optional
 */
public class ServerPreferences {

    /** Preference node ID — UI bundle writes, plugin reads. */
    public static final String PREFERENCE_NODE =
            "io.github.kaluchi.jdtbridge.ui";

    // --- Local socket ---

    /** 0 = auto-assigned (default), 1024-65535 = fixed */
    public static final String LOCAL_PORT = "localPort";

    /** Whether to regenerate local token on Eclipse restart.
     *  Default: true (current behavior). */
    public static final String LOCAL_REGENERATE_TOKEN =
            "localRegenerateToken";

    /** Persisted local token (used when regenerate is off). */
    public static final String LOCAL_TOKEN = "localToken";

    // --- Remote socket ---

    /** Enable remote socket on 0.0.0.0. Default: false. */
    public static final String REMOTE_ENABLED = "remoteEnabled";

    /** Remote socket port. 0 = auto-assigned, 1024-65535 = fixed */
    public static final String REMOTE_PORT = "remotePort";

    /** Whether to regenerate remote token on Eclipse restart.
     *  Default: false (fixed token for containers). */
    public static final String REMOTE_REGENERATE_TOKEN =
            "remoteRegenerateToken";

    /** Persisted remote token. */
    public static final String REMOTE_TOKEN = "remoteToken";

    // --- Resolution ---

    /** Read local port from preferences. */
    public static int resolveLocalPort() {
        try {
            return Platform.getPreferencesService().getInt(
                    PREFERENCE_NODE, LOCAL_PORT, 0, null);
        } catch (Exception e) {
            Log.warn("Failed to read local port preference", e);
            return 0;
        }
    }

    /** Whether local token should regenerate on restart. */
    public static boolean resolveLocalRegenerateToken() {
        try {
            return Platform.getPreferencesService().getBoolean(
                    PREFERENCE_NODE, LOCAL_REGENERATE_TOKEN,
                    true, null);
        } catch (Exception e) {
            return true;
        }
    }

    /** Read persisted local token (when regenerate is off). */
    public static String resolveLocalToken() {
        try {
            return Platform.getPreferencesService().getString(
                    PREFERENCE_NODE, LOCAL_TOKEN, "", null);
        } catch (Exception e) {
            return "";
        }
    }

    /** Whether remote socket is enabled. */
    public static boolean resolveRemoteEnabled() {
        try {
            return Platform.getPreferencesService()
                    .getBoolean(PREFERENCE_NODE, REMOTE_ENABLED,
                            false, null);
        } catch (Exception e) {
            return false;
        }
    }

    /** Read remote port from preferences. */
    public static int resolveRemotePort() {
        try {
            return Platform.getPreferencesService().getInt(
                    PREFERENCE_NODE, REMOTE_PORT, 0, null);
        } catch (Exception e) {
            Log.warn("Failed to read remote port preference", e);
            return 0;
        }
    }

    /** Whether remote token should regenerate on restart. */
    public static boolean resolveRemoteRegenerateToken() {
        try {
            return Platform.getPreferencesService().getBoolean(
                    PREFERENCE_NODE, REMOTE_REGENERATE_TOKEN,
                    false, null);
        } catch (Exception e) {
            return false;
        }
    }

    /** Read persisted remote token. */
    public static String resolveRemoteToken() {
        try {
            return Platform.getPreferencesService().getString(
                    PREFERENCE_NODE, REMOTE_TOKEN, "", null);
        } catch (Exception e) {
            return "";
        }
    }

}
