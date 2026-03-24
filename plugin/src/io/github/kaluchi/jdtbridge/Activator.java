package io.github.kaluchi.jdtbridge;

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
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {

    private static final String HOME_DIR = ".jdtbridge";
    private static final String INSTANCES_DIR = "instances";
    private static final int TOKEN_BYTES = 16;

    private HttpServer server;
    private Path bridgeFile;

    @Override
    public void start(BundleContext context) throws Exception {
        // Skip server in test environments (Tycho surefire)
        String workspace = ResourcesPlugin.getWorkspace().getRoot()
                .getLocation().toOSString();
        if (workspace.contains("target" + File.separator
                + "work")) {
            Log.info("Test environment detected — skipping server");
            return;
        }

        String token = generateToken();

        server = new HttpServer();
        server.setToken(token);
        server.start();

        int port = server.getPort();
        String version = context.getBundle().getVersion().toString();
        String location = context.getBundle().getLocation();
        writeBridgeFile(port, token, version, location);

        Log.info("HTTP server started on port " + port);
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

        String content = Json.object()
                .put("port", port)
                .put("token", token)
                .put("pid", pid)
                .put("workspace", workspace)
                .put("version", version)
                .put("location", location)
                .toString() + "\n";

        Files.writeString(bridgeFile, content);
        setPosixOwnerOnly(bridgeFile);
        setPosixOwnerOnly(instancesDir);
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

    static Path getHome() {
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
