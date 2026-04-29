package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Unit tests for {@link ServerPreferences}. Each test writes the
 * relevant key into the InstanceScope node, reads it back via the
 * resolveX entry point, then clears the key in @AfterEach so the
 * next test starts from a known default.
 */
public class ServerPreferencesTest {

    private IEclipsePreferences node;

    @BeforeEach
    void getNode() {
        node = InstanceScope.INSTANCE.getNode(
                ServerPreferences.PREFERENCE_NODE);
    }

    @AfterEach
    void clearAllKeys() throws BackingStoreException {
        for (String key : new String[] {
                ServerPreferences.LOCAL_PORT,
                ServerPreferences.LOCAL_REGENERATE_TOKEN,
                ServerPreferences.LOCAL_TOKEN,
                ServerPreferences.REMOTE_ENABLED,
                ServerPreferences.REMOTE_PORT,
                ServerPreferences.REMOTE_REGENERATE_TOKEN,
                ServerPreferences.REMOTE_TOKEN,
        }) {
            node.remove(key);
        }
        node.flush();
    }

    @Test
    void utilityClassInstantiable() {
        assertNotNull(new ServerPreferences());
    }

    // ---- LOCAL ----

    @Test
    void localPortDefaultsToZero() {
        assertEquals(0, ServerPreferences.resolveLocalPort());
    }

    @Test
    void localPortReadsConfiguredValue()
            throws BackingStoreException {
        node.putInt(ServerPreferences.LOCAL_PORT, 8080);
        node.flush();
        assertEquals(8080,
                ServerPreferences.resolveLocalPort());
    }

    @Test
    void localRegenerateTokenDefaultsToTrue() {
        assertTrue(ServerPreferences
                .resolveLocalRegenerateToken());
    }

    @Test
    void localRegenerateTokenReadsFalse()
            throws BackingStoreException {
        node.putBoolean(
                ServerPreferences.LOCAL_REGENERATE_TOKEN,
                false);
        node.flush();
        assertFalse(ServerPreferences
                .resolveLocalRegenerateToken());
    }

    @Test
    void localTokenDefaultsToEmpty() {
        assertEquals("",
                ServerPreferences.resolveLocalToken());
    }

    @Test
    void localTokenReadsValue()
            throws BackingStoreException {
        node.put(ServerPreferences.LOCAL_TOKEN,
                "abc123-secret");
        node.flush();
        assertEquals("abc123-secret",
                ServerPreferences.resolveLocalToken());
    }

    // ---- REMOTE ----

    @Test
    void remoteEnabledDefaultsToFalse() {
        assertFalse(
                ServerPreferences.resolveRemoteEnabled());
    }

    @Test
    void remoteEnabledReadsTrue()
            throws BackingStoreException {
        node.putBoolean(
                ServerPreferences.REMOTE_ENABLED, true);
        node.flush();
        assertTrue(
                ServerPreferences.resolveRemoteEnabled());
    }

    @Test
    void remotePortDefaultsToZero() {
        assertEquals(0,
                ServerPreferences.resolveRemotePort());
    }

    @Test
    void remotePortReadsConfiguredValue()
            throws BackingStoreException {
        node.putInt(ServerPreferences.REMOTE_PORT, 7777);
        node.flush();
        assertEquals(7777,
                ServerPreferences.resolveRemotePort());
    }

    @Test
    void remoteRegenerateTokenDefaultsToFalse() {
        assertFalse(ServerPreferences
                .resolveRemoteRegenerateToken());
    }

    @Test
    void remoteRegenerateTokenReadsTrue()
            throws BackingStoreException {
        node.putBoolean(
                ServerPreferences.REMOTE_REGENERATE_TOKEN,
                true);
        node.flush();
        assertTrue(ServerPreferences
                .resolveRemoteRegenerateToken());
    }

    @Test
    void remoteTokenDefaultsToEmpty() {
        assertEquals("",
                ServerPreferences.resolveRemoteToken());
    }

    @Test
    void remoteTokenReadsValue()
            throws BackingStoreException {
        node.put(ServerPreferences.REMOTE_TOKEN,
                "remote-secret-42");
        node.flush();
        assertEquals("remote-secret-42",
                ServerPreferences.resolveRemoteToken());
    }
}
