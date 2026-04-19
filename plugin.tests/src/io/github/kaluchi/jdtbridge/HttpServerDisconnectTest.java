package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;

import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link HttpServer#isClientDisconnect}. The
 * method decides whether a request-handling throwable came from
 * the peer bailing (log as info) or from a server-side fault
 * (log as error). Both branches are observable in the Eclipse
 * Error Log, so the classifier is hot.
 */
class HttpServerDisconnectTest {

    @Test
    void socketExceptionAtAnyDepthCountsAsDisconnect() {
        assertTrue(HttpServer.isClientDisconnect(
                new SocketException("anything")));
        // Wrapped: cause chain carries the signal
        assertTrue(HttpServer.isClientDisconnect(
                new IOException("outer",
                        new SocketException("inner"))));
    }

    @Test
    void eofExceptionCountsAsDisconnect() {
        assertTrue(HttpServer.isClientDisconnect(
                new EOFException("client closed early")));
    }

    @Test
    void posixBrokenPipeSignatureMatches() {
        assertTrue(HttpServer.isClientDisconnect(
                new IOException(
                        "java.io.IOException: Broken pipe")));
    }

    @Test
    void windowsEnglishAbortedSignatureMatches() {
        assertTrue(HttpServer.isClientDisconnect(
                new IOException(
                        "An established connection was aborted "
                        + "by the software in your host machine")));
        assertTrue(HttpServer.isClientDisconnect(
                new IOException(
                        "An existing connection was forcibly "
                        + "closed by the remote host")));
    }

    @Test
    void connectionResetSignatureMatches() {
        assertTrue(HttpServer.isClientDisconnect(
                new IOException("Connection reset by peer")));
    }

    @Test
    void windowsRussianSignatureMatches() {
        assertTrue(HttpServer.isClientDisconnect(
                new IOException(
                        "Программа на вашем хост-компьютере "
                        + "разорвала установленное подключение")));
    }

    @Test
    void windowsGermanSignatureMatches() {
        assertTrue(HttpServer.isClientDisconnect(
                new IOException(
                        "Eine bestehende Verbindung wurde vom "
                        + "Host-Computer zurückgesetzt abgebrochen")));
    }

    @Test
    void actualServerFaultIsNotMisclassified() {
        assertFalse(HttpServer.isClientDisconnect(
                new IllegalStateException("handler threw")));
        assertFalse(HttpServer.isClientDisconnect(
                new NullPointerException("bad state")));
        // Generic IOException without a disconnect substring — a
        // real disk / encoding failure must still surface as
        // Log.error so it's not swept under the rug.
        assertFalse(HttpServer.isClientDisconnect(
                new IOException("disk full")));
    }

    @Test
    void nullThrowableIsNotDisconnect() {
        assertFalse(HttpServer.isClientDisconnect(null));
    }
}
