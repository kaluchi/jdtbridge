package io.github.kaluchi.jdtbridge;

/**
 * Detect remote-disconnect signatures inside wrapped exception
 * messages. The JVM doesn't report a single canonical SocketException
 * for client-disconnect across platforms — Linux says "Broken pipe",
 * macOS "Connection reset", Windows surfaces a localized message
 * from the OS, and we see them all flowing through wrapper layers
 * (HTTP server, IO streams, executor reject) by message-substring.
 *
 * <p>Extracted out of {@link HttpServer} so the locale list has a
 * single home and is unit-testable without the server lifecycle.
 */
final class DisconnectSignatures {

    private DisconnectSignatures() { }

    /**
     * @return {@code true} if {@code msg} carries any known
     *         remote-disconnect signature.
     */
    static boolean matches(String msg) {
        // POSIX / generic
        if (msg.contains("Broken pipe")) return true;
        if (msg.contains("Connection reset")) return true;
        // Windows English — WSAECONNABORTED
        if (msg.contains("connection was aborted")) return true;
        if (msg.contains("established connection was aborted"))
            return true;
        if (msg.contains("existing connection was forcibly closed"))
            return true;
        // Windows Russian (ru_RU)
        if (msg.contains("разорвала")) return true;
        if (msg.contains("разорвано")) return true;
        if (msg.contains("прервано")) return true;
        // Windows German (de_DE)
        if (msg.contains("Verbindung wurde")
                && msg.contains("abgebrochen")) return true;
        if (msg.contains("bestehende Verbindung wurde")) return true;
        // Windows French (fr_FR)
        if (msg.contains("connexion existante a")
                && msg.contains("interrompue")) return true;
        if (msg.contains("connexion a été abandonnée")) return true;
        // Windows Spanish (es_ES)
        if (msg.contains("conexión existente")
                && msg.contains("forzosamente")) return true;
        return false;
    }
}
