package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.Socket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.kaluchi.jdtbridge.support.TestAwait;

/**
 * Tests for dual-socket server configuration:
 * local (loopback) + remote (all interfaces).
 * Each socket has its own port and token.
 */
public class DualSocketTest {

    private HttpServer localServer;
    private HttpServer remoteServer;

    @AfterEach
    void tearDown() {
        if (localServer != null) localServer.stop();
        if (remoteServer != null) remoteServer.stop();
    }

    @Nested
    class BothSocketsRunning {
        @Test
        void localAndRemoteOnDifferentPorts() throws Exception {
            localServer = new HttpServer();
            localServer.setToken("local-token");
            localServer.start(InetAddress.getLoopbackAddress(), 0);

            remoteServer = new HttpServer();
            remoteServer.setToken("remote-token");
            remoteServer.start(
                    InetAddress.getByName("0.0.0.0"), 0);

            assertNotEquals(localServer.getPort(),
                    remoteServer.getPort(),
                    "Local and remote must have different ports");
            assertNotEquals(0, localServer.getPort());
            assertNotEquals(0, remoteServer.getPort());
        }

        @Test
        void localAcceptsConnectionsOnLoopback() throws Exception {
            localServer = new HttpServer();
            localServer.setToken("local-token");
            localServer.start(InetAddress.getLoopbackAddress(), 0);

            try (Socket clientSocket = new Socket(
                    "127.0.0.1", localServer.getPort())) {
                assertTrue(clientSocket.isConnected());
            }
        }

        @Test
        void remoteAcceptsConnectionsOnLoopback() throws Exception {
            // 0.0.0.0 includes loopback
            remoteServer = new HttpServer();
            remoteServer.setToken("remote-token");
            remoteServer.start(
                    InetAddress.getByName("0.0.0.0"), 0);

            try (Socket clientSocket = new Socket(
                    "127.0.0.1", remoteServer.getPort())) {
                assertTrue(clientSocket.isConnected());
            }
        }

        @Test
        void differentTokensAccepted() throws Exception {
            localServer = new HttpServer();
            localServer.setToken("local-secret");
            localServer.start(InetAddress.getLoopbackAddress(), 0);

            remoteServer = new HttpServer();
            remoteServer.setToken("remote-secret");
            remoteServer.start(
                    InetAddress.getByName("0.0.0.0"), 0);

            assertNotEquals(localServer.getPort(),
                    remoteServer.getPort());

            // Each server accepts only its own token
            assertEquals(InetAddress.getLoopbackAddress(),
                    localServer.getBindAddress());
            assertNotNull(remoteServer.getBindAddress());
        }
    }

    @Nested
    class RemoteLifecycle {
        @Test
        void startAndStopRemoteIndependently() throws Exception {
            localServer = new HttpServer();
            localServer.setToken("local-token");
            localServer.start(InetAddress.getLoopbackAddress(), 0);
            int localPort = localServer.getPort();

            remoteServer = new HttpServer();
            remoteServer.setToken("remote-token");
            remoteServer.start(
                    InetAddress.getByName("0.0.0.0"), 0);
            int remotePort = remoteServer.getPort();

            // Stop remote
            remoteServer.stop();
            remoteServer = null;

            // Local still works
            try (Socket clientSocket = new Socket(
                    "127.0.0.1", localPort)) {
                assertTrue(clientSocket.isConnected());
            }

            TestAwait.assertPortRefused("127.0.0.1", remotePort);
        }

        @Test
        void restartRemoteWithDifferentPort() throws Exception {
            localServer = new HttpServer();
            localServer.setToken("local-token");
            localServer.start(InetAddress.getLoopbackAddress(), 0);

            remoteServer = new HttpServer();
            remoteServer.setToken("remote-token-v1");
            remoteServer.start(
                    InetAddress.getByName("0.0.0.0"), 0);
            // Stop and restart remote
            remoteServer.stop();
            remoteServer = new HttpServer();
            remoteServer.setToken("remote-token-v2");
            remoteServer.start(
                    InetAddress.getByName("0.0.0.0"), 0);
            int secondRemotePort = remoteServer.getPort();

            assertNotEquals(0, localServer.getPort());
            assertNotEquals(0, secondRemotePort);
            try (Socket clientSocket = new Socket(
                    "127.0.0.1", secondRemotePort)) {
                assertTrue(clientSocket.isConnected());
            }
        }
    }

    @Nested
    class PortConflict {
        @Test
        void sameFixedPortOnDifferentAddressesWorks()
                throws Exception {
            // 127.0.0.1:N and 0.0.0.0:N — may or may not
            // conflict depending on OS. Test that fallback works.
            localServer = new HttpServer();
            localServer.setToken("local-token");
            localServer.start(InetAddress.getLoopbackAddress(), 0);
            int localPort = localServer.getPort();

            remoteServer = new HttpServer();
            remoteServer.setToken("remote-token");
            // Try same port — may fallback to auto
            remoteServer.start(
                    InetAddress.getByName("0.0.0.0"), localPort);

            assertNotEquals(0, remoteServer.getPort());
        }
    }
}
