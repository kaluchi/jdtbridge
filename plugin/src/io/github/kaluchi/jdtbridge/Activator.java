package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
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

    private HttpServer server;
    private Path bridgeFile;
    private String currentToken;
    private BundleContext bundleContext;
    private volatile boolean rebindScheduled;

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

        bundleContext = context;
        currentToken = generateToken();

        var bindAddress = ServerPreferences.resolveBindAddress();
        int configuredPort = ServerPreferences.resolveFixedPort();

        server = new HttpServer();
        server.setToken(currentToken);
        server.start(bindAddress, configuredPort);

        String version = context.getBundle().getVersion().toString();
        String location = context.getBundle().getLocation();
        writeBridgeFile(server.getPort(), currentToken,
                version, location);

        Log.info("HTTP server started on "
                + bindAddress.getHostAddress() + ":"
                + server.getPort());

        registerPreferenceListener();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (server != null) {
            server.stop();
            server = null;
        }
        deleteBridgeFile();
        Log.info("HTTP server stopped");
    }

    private void writeBridgeFile(int port, String token,
            String version, String location) throws IOException {
        String workspace = ResourcesPlugin.getWorkspace().getRoot()
                .getLocation().toOSString();
        long pid = ProcessHandle.current().pid();

        Path homeDir = resolveHome();
        Path instancesDir = homeDir.resolve(INSTANCES_DIR);
        Files.createDirectories(instancesDir);

        String hash = workspaceHash(workspace);
        bridgeFile = instancesDir.resolve(hash + ".json");

        var obj = new JsonObject();
        obj.addProperty("port", port);
        obj.addProperty("token", token);
        obj.addProperty("pid", pid);
        obj.addProperty("workspace", workspace);
        obj.addProperty("version", version);
        obj.addProperty("location", location);
        String content = obj.toString() + "\n";

        Files.writeString(bridgeFile, content);
        setPosixOwnerOnly(bridgeFile);
        setPosixOwnerOnly(instancesDir);
    }

    private void registerPreferenceListener() {
        try {
            IEclipsePreferences prefNode = InstanceScope.INSTANCE
                    .getNode(ServerPreferences.PREFERENCE_NODE);
            prefNode.addPreferenceChangeListener(
                    preferenceChange -> {
                String changedKey = preferenceChange.getKey();
                if (ServerPreferences.HTTP_BIND_ADDRESS
                        .equals(changedKey)
                        || ServerPreferences.HTTP_FIXED_PORT
                                .equals(changedKey)) {
                    scheduleRebind();
                }
            });
        } catch (Exception preferenceListenerException) {
            Log.warn("Failed to register preference listener",
                    preferenceListenerException);
        }
    }

    /**
     * Coalesce multiple preference changes (address + port written
     * sequentially) into a single rebind. Runs off UI thread.
     */
    private void scheduleRebind() {
        if (rebindScheduled) return;
        rebindScheduled = true;
        new Thread(() -> {
            try {
                Thread.sleep(100); // coalesce rapid changes
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            rebindScheduled = false;
            performRebind();
        }, "jdtbridge-rebind").start();
    }

    private void performRebind() {
        if (server == null) return;
        try {
            var bindAddress = ServerPreferences.resolveBindAddress();
            int fixedPort = ServerPreferences.resolveFixedPort();
            server.rebind(bindAddress, fixedPort);

            String version = bundleContext.getBundle()
                    .getVersion().toString();
            String location = bundleContext.getBundle().getLocation();
            writeBridgeFile(server.getPort(), currentToken,
                    version, location);

            Log.info("Server rebound to "
                    + bindAddress.getHostAddress() + ":"
                    + server.getPort());
        } catch (IOException rebindException) {
            Log.error("Failed to rebind server", rebindException);
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

    private void deleteBridgeFile() {
        if (bridgeFile != null) {
            try {
                Files.deleteIfExists(bridgeFile);
            } catch (IOException e) {
                Log.warn("Failed to delete bridge file", e);
            }
        }
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
