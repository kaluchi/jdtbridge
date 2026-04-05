package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Set;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {

    private static final String HOME_DIR = ".jdtbridge";
    private static final String INSTANCES_DIR = "instances";
    private static final int TOKEN_BYTES = 16;

    private static Activator instance;

    private HttpServer localServer;
    private HttpServer remoteServer;
    private Path localBridgeFile;
    private String localToken;
    private String remoteToken;
    private BundleContext bundleContext;
    private final java.util.concurrent.atomic.AtomicBoolean
            rebindScheduled = new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public void start(BundleContext context) throws Exception {
        // Skip server in test environments (Tycho surefire, PDE JUnit)
        if ("true".equals(System.getProperty("jdtbridge.integration-tests"))
                || "true".equals(
                        System.getProperty("eclipse.pde.launch"))) {
            Log.info("Test environment — skipping server");
            return;
        }
        String workspace = ResourcesPlugin.getWorkspace().getRoot()
                .getLocation().toOSString();
        if (workspace.contains("target" + File.separator
                + "work")) {
            Log.info("Tycho test environment — skipping server");
            return;
        }

        instance = this;
        bundleContext = context;
        String version = context.getBundle().getVersion().toString();
        String location = context.getBundle().getLocation();

        // Local socket — always on loopback
        localToken = resolveLocalToken();
        int localPort = ServerPreferences.resolveLocalPort();
        localServer = new HttpServer();
        localServer.setToken(localToken);
        localServer.start(InetAddress.getLoopbackAddress(), localPort);
        Log.info("Local server on 127.0.0.1:"
                + localServer.getPort());

        // Remote socket — optional
        if (ServerPreferences.resolveRemoteEnabled()) {
            startRemoteServer(version, location);
        }

        localBridgeFile = writeBridgeFile(version, location);

        registerPreferenceListener();
    }

    private void startRemoteServer(String version, String location)
            throws IOException {
        remoteToken = resolveRemoteToken();
        int remotePort = ServerPreferences.resolveRemotePort();
        remoteServer = new HttpServer();
        remoteServer.setToken(remoteToken);
        remoteServer.start(InetAddress.getByName("0.0.0.0"),
                remotePort);
        Log.info("Remote server on 0.0.0.0:"
                + remoteServer.getPort());
    }

    private void stopRemoteServer() {
        if (remoteServer != null) {
            remoteServer.stop();
            remoteServer = null;
        }
    }

    private String resolveLocalToken() {
        if (ServerPreferences.resolveLocalRegenerateToken()) {
            return generateToken();
        }
        String persisted = ServerPreferences.resolveLocalToken();
        return persisted.isEmpty() ? generateToken() : persisted;
    }

    private String resolveRemoteToken() {
        if (ServerPreferences.resolveRemoteRegenerateToken()) {
            return generateToken();
        }
        String persisted = ServerPreferences.resolveRemoteToken();
        return persisted.isEmpty() ? generateToken() : persisted;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (localServer != null) {
            localServer.stop();
            localServer = null;
        }
        stopRemoteServer();
        if (localBridgeFile != null) {
            try { Files.deleteIfExists(localBridgeFile); }
            catch (IOException deleteException) {
                Log.warn("Failed to delete local bridge file",
                        deleteException);
            }
        }
        instance = null;
        Log.info("HTTP server stopped");
    }

    private Path writeBridgeFile(String version, String location)
            throws IOException {
        String workspace = ResourcesPlugin.getWorkspace().getRoot()
                .getLocation().toOSString();
        long pid = ProcessHandle.current().pid();

        Path homeDir = resolveHome();
        Path instancesDir = homeDir.resolve(INSTANCES_DIR);
        Files.createDirectories(instancesDir);

        String hash = workspaceHash(workspace);
        Path bridgeFilePath = instancesDir.resolve(hash + ".json");

        var obj = new JsonObject();
        obj.addProperty("port", localServer.getPort());
        obj.addProperty("token", localToken);
        obj.addProperty("host", "127.0.0.1");
        if (remoteServer != null) {
            obj.addProperty("remotePort", remoteServer.getPort());
            obj.addProperty("remoteToken", remoteToken);
        }
        obj.addProperty("pid", pid);
        obj.addProperty("workspace", workspace);
        obj.addProperty("version", version);
        obj.addProperty("location", location);
        String content = obj.toString() + "\n";

        Files.writeString(bridgeFilePath, content);
        setPosixOwnerOnly(bridgeFilePath);
        setPosixOwnerOnly(instancesDir);
        return bridgeFilePath;
    }

    private void registerPreferenceListener() {
        try {
            IEclipsePreferences prefNode = InstanceScope.INSTANCE
                    .getNode(ServerPreferences.PREFERENCE_NODE);
            prefNode.addPreferenceChangeListener(
                    preferenceChange -> scheduleRebind());
        } catch (Exception preferenceListenerException) {
            Log.warn("Failed to register preference listener",
                    preferenceListenerException);
        }
    }

    private void scheduleRebind() {
        if (!rebindScheduled.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            rebindScheduled.set(false);
            performRebind();
        }, "jdtbridge-rebind").start();
    }

    private void performRebind() {
        String version = bundleContext.getBundle()
                .getVersion().toString();
        String location = bundleContext.getBundle().getLocation();

        // Local rebind
        if (localServer != null) {
            try {
                localToken = resolveLocalToken();
                int localPort = ServerPreferences.resolveLocalPort();
                localServer.setToken(localToken);
                localServer.rebind(
                        InetAddress.getLoopbackAddress(), localPort);
                Log.info("Local server rebound to 127.0.0.1:"
                        + localServer.getPort());
            } catch (IOException localRebindException) {
                Log.error("Failed to rebind local server",
                        localRebindException);
            }
        }

        // Remote toggle
        boolean remoteEnabled =
                ServerPreferences.resolveRemoteEnabled();
        if (remoteEnabled && remoteServer == null) {
            try {
                startRemoteServer(version, location);
            } catch (IOException remoteStartException) {
                Log.error("Failed to start remote server",
                        remoteStartException);
            }
        } else if (!remoteEnabled && remoteServer != null) {
            stopRemoteServer();
            Log.info("Remote server stopped");
        } else if (remoteEnabled && remoteServer != null) {
            try {
                remoteToken = resolveRemoteToken();
                int remotePort =
                        ServerPreferences.resolveRemotePort();
                remoteServer.setToken(remoteToken);
                remoteServer.rebind(
                        InetAddress.getByName("0.0.0.0"),
                        remotePort);
                Log.info("Remote server rebound to 0.0.0.0:"
                        + remoteServer.getPort());
            } catch (IOException remoteRebindException) {
                Log.error("Failed to rebind remote server",
                        remoteRebindException);
            }
        }

        // Rewrite bridge file with current state (both sockets)
        try {
            localBridgeFile = writeBridgeFile(version, location);
        } catch (IOException bridgeFileException) {
            Log.error("Failed to write bridge file",
                    bridgeFileException);
        }
    }

    private static Path resolveHome() {
        String env = System.getenv("JDTBRIDGE_HOME");
        if (env != null && !env.isEmpty()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), HOME_DIR);
    }

    private static void setPosixOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException e) {
            // Windows — POSIX permissions not available
        } catch (IOException e) {
            Log.warn("Failed to set permissions on " + path, e);
        }
    }

    static String workspaceHash(String workspace) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(workspace.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static Activator getInstance() {
        return instance;
    }

    public int getLocalPort() {
        return localServer != null ? localServer.getPort() : -1;
    }

    public String getLocalToken() {
        return localToken;
    }

    public int getRemotePort() {
        return remoteServer != null ? remoteServer.getPort() : -1;
    }

    public String getRemoteToken() {
        return remoteToken;
    }

    public boolean isRemoteRunning() {
        return remoteServer != null;
    }

    public static Path getHome() {
        return resolveHome();
    }

    static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(TOKEN_BYTES * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
