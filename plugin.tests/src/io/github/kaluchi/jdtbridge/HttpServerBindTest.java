package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for HttpServer configurable bind address and port.
 * Covers: custom bind, fixed port, port conflict fallback,
 * hot rebind, and bind address accessor.
 */
public class HttpServerBindTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Nested
    class StartWithDefaults {
        @Test
        void defaultStartBindsToLoopback() throws Exception {
            server = new HttpServer();
            server.start();
            int port = server.getPort();
            assertTrue(port > 0, "Should get assigned port");

            // Verify reachable on loopback
            try (Socket socket = new Socket("127.0.0.1", port)) {
                assertTrue(socket.isConnected());
            }
        }
    }

    @Nested
    class StartWithCustomBindAddress {
        @Test
        void startWithLoopbackAddress() throws Exception {
            server = new HttpServer();
            server.start(InetAddress.getLoopbackAddress(), 0);
            assertTrue(server.getPort() > 0);
            assertEquals(
                    InetAddress.getLoopbackAddress(),
                    server.getBindAddress());
        }

        @Test
        void startWithAllInterfaces() throws Exception {
            server = new HttpServer();
            server.start(InetAddress.getByName("0.0.0.0"), 0);
            assertTrue(server.getPort() > 0);
            assertNotNull(server.getBindAddress());
        }
    }

    @Nested
    class StartWithFixedPort {
        @Test
        void fixedPortIsUsed() throws Exception {
            int fixedPort = findFreePort();
            server = new HttpServer();
            server.start(InetAddress.getLoopbackAddress(), fixedPort);
            assertEquals(fixedPort, server.getPort());
        }

        @Test
        void conflictFallsBackToAutoPort() throws Exception {
            // Occupy a port
            int occupiedPort;
            try (ServerSocket blocker = new ServerSocket(0, 50,
                    InetAddress.getLoopbackAddress())) {
                occupiedPort = blocker.getLocalPort();

                // Start server requesting the occupied port
                server = new HttpServer();
                server.start(InetAddress.getLoopbackAddress(),
                        occupiedPort);

                // Should get a different (auto-assigned) port
                assertNotEquals(occupiedPort, server.getPort());
                assertTrue(server.getPort() > 0);
            }
        }

        @Test
        void autoPortWhenZero() throws Exception {
            server = new HttpServer();
            server.start(InetAddress.getLoopbackAddress(), 0);
            assertTrue(server.getPort() > 0);
        }
    }

    @Nested
    class Rebind {
        @Test
        void rebindChangesPort() throws Exception {
            server = new HttpServer();
            server.setToken("test-token");
            server.start(InetAddress.getLoopbackAddress(), 0);
            int oldPort = server.getPort();

            server.rebind(InetAddress.getLoopbackAddress(), 0);
            int newPort = server.getPort();

            assertNotEquals(oldPort, newPort,
                    "Rebind should assign a new port");
            assertTrue(newPort > 0);
        }

        @Test
        void rebindToFixedPort() throws Exception {
            server = new HttpServer();
            server.setToken("test-token");
            server.start(InetAddress.getLoopbackAddress(), 0);

            int fixedPort = findFreePort();
            server.rebind(InetAddress.getLoopbackAddress(), fixedPort);
            assertEquals(fixedPort, server.getPort());
        }

        @Test
        void rebindOldPortStopsAccepting() throws Exception {
            server = new HttpServer();
            server.setToken("test-token");
            server.start(InetAddress.getLoopbackAddress(), 0);
            int oldPort = server.getPort();

            server.rebind(InetAddress.getLoopbackAddress(), 0);
            int newPort = server.getPort();

            // New port should accept connections
            try (Socket socket = new Socket("127.0.0.1", newPort)) {
                assertTrue(socket.isConnected());
            }

            // Old port should refuse connections
            boolean refused = false;
            try (Socket socket = new Socket("127.0.0.1", oldPort)) {
                // might connect to something else reusing the port
            } catch (Exception e) {
                refused = true;
            }
            assertTrue(refused,
                    "Old port should refuse connections after rebind");
        }

        @Test
        void rebindConflictFallsBack() throws Exception {
            server = new HttpServer();
            server.setToken("test-token");
            server.start(InetAddress.getLoopbackAddress(), 0);

            // Occupy a port
            try (ServerSocket blocker = new ServerSocket(0, 50,
                    InetAddress.getLoopbackAddress())) {
                int occupiedPort = blocker.getLocalPort();

                server.rebind(InetAddress.getLoopbackAddress(),
                        occupiedPort);

                // Should get auto-assigned, not the occupied one
                assertNotEquals(occupiedPort, server.getPort());
                assertTrue(server.getPort() > 0);
            }
        }

        @Test
        void rebindAcceptsConnectionsOnNewPort() throws Exception {
            server = new HttpServer();
            server.setToken("tok");
            server.start(InetAddress.getLoopbackAddress(), 0);

            server.rebind(InetAddress.getLoopbackAddress(), 0);
            int newPort = server.getPort();

            // ServerSocket backlog accepts connections before
            // accept() is called. Verify TCP connect succeeds.
            try (Socket socket = new Socket("127.0.0.1", newPort)) {
                socket.setSoTimeout(3000);
                assertTrue(socket.isConnected(),
                        "Should accept on new port");
            }
        }
    }

    @Nested
    class BindAddressAccessor {
        @Test
        void returnsNullBeforeStart() {
            server = new HttpServer();
            assertNull(server.getBindAddress());
        }

        @Test
        void returnsAddressAfterStart() throws Exception {
            server = new HttpServer();
            server.start(InetAddress.getLoopbackAddress(), 0);
            assertEquals(InetAddress.getLoopbackAddress(),
                    server.getBindAddress());
        }
    }

    // --- helpers ---

    private static int findFreePort() throws Exception {
        try (ServerSocket tempSocket = new ServerSocket(0)) {
            return tempSocket.getLocalPort();
        }
    }
}
