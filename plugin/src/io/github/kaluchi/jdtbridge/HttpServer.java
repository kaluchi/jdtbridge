package io.github.kaluchi.jdtbridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Minimal HTTP server on a raw ServerSocket.
 * Handles GET requests, parses path + query params, dispatches to handlers.
 * Binds to loopback only — not reachable from the network.
 */
public class HttpServer {

    private final SearchHandler search = new SearchHandler();
    private final DiagnosticsHandler diagnostics =
            new DiagnosticsHandler();
    private final RefactoringHandler refactoring =
            new RefactoringHandler();
    private final EditorHandler editor = new EditorHandler();
    private final TestHandler testHandler = new TestHandler();
    private final ProjectHandler projectInfo = new ProjectHandler();
    private final ExecutorService executor =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "jdtbridge-req");
                t.setDaemon(true);
                return t;
            });
    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private volatile String token;

    /** Response with content type, optional headers, and body. */
    record Response(String contentType, Map<String, String> headers,
            String body) {
        static Response json(String json) {
            return new Response("application/json", Map.of(), json);
        }

        static Response text(String body, Map<String, String> headers) {
            return new Response("text/plain", headers, body);
        }
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(
                0, 50, InetAddress.getLoopbackAddress());
        running = true;
        Thread t = new Thread(this::acceptLoop, "jdtbridge-http");
        t.setDaemon(true);
        t.start();
    }

    /** Returns the actual port the server is listening on. */
    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) { /* expected on shutdown */ }
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                executor.submit(() -> handle(socket));
            } catch (IOException e) {
                if (running) {
                    Log.error("Accept error", e);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(30_000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            // Read headers
            String authHeader = null;
            String line;
            while ((line = reader.readLine()) != null
                    && !line.isEmpty()) {
                if (line.regionMatches(true, 0,
                        "Authorization:", 0, 14)) {
                    authHeader = line.substring(14).trim();
                }
            }

            // Token auth check
            if (token != null && !token.isEmpty()) {
                String expected = "Bearer " + token;
                if (authHeader == null
                        || !MessageDigest.isEqual(
                                expected.getBytes(StandardCharsets.UTF_8),
                                authHeader.getBytes(StandardCharsets.UTF_8))) {
                    sendError(socket, 401, "Unauthorized");
                    return;
                }
            }

            String fullPath = parts[1];
            String path;
            Map<String, String> params;
            int q = fullPath.indexOf('?');
            if (q >= 0) {
                path = fullPath.substring(0, q);
                params = parseQuery(fullPath.substring(q + 1));
            } else {
                path = fullPath;
                params = Map.of();
            }

            Response resp = dispatch(path, params);
            sendResponse(socket, resp);
        } catch (Exception e) {
            Log.error("Request error", e);
        }
    }

    private void sendResponse(Socket socket, Response resp)
            throws IOException {
        byte[] bodyBytes = resp.body().getBytes(StandardCharsets.UTF_8);
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 200 OK\r\n");
        header.append("Content-Type: ").append(resp.contentType())
                .append("; charset=utf-8\r\n");
        header.append("Content-Length: ")
                .append(bodyBytes.length).append("\r\n");
        header.append("Connection: close\r\n");
        for (var entry : resp.headers().entrySet()) {
            header.append(entry.getKey()).append(": ")
                    .append(entry.getValue()).append("\r\n");
        }
        header.append("\r\n");

        OutputStream out = socket.getOutputStream();
        out.write(header.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    private Response dispatch(String path, Map<String, String> params) {
        try {
            return switch (path) {
                case "/projects" -> Response.json(
                        search.handleProjects());
                case "/project-info" -> Response.json(
                        projectInfo.handleProjectInfo(params));
                case "/find" -> Response.json(
                        search.handleFind(params));
                case "/references" -> Response.json(
                        search.handleReferences(params));
                case "/subtypes" -> Response.json(
                        search.handleSubtypes(params));
                case "/hierarchy" -> Response.json(
                        search.handleHierarchy(params));
                case "/implementors" -> Response.json(
                        search.handleImplementors(params));
                case "/errors" -> Response.json(
                        diagnostics.handleErrors(params));
                case "/build" -> Response.json(
                        diagnostics.handleBuild(params));
                case "/type-info" -> Response.json(
                        search.handleTypeInfo(params));
                case "/source" -> search.handleSource(params);
                case "/organize-imports" -> Response.json(
                        refactoring.handleOrganizeImports(params));
                case "/format" -> Response.json(
                        refactoring.handleFormat(params));
                case "/rename" -> Response.json(
                        refactoring.handleRename(params));
                case "/move" -> Response.json(
                        refactoring.handleMove(params));
                case "/test" -> Response.json(
                        testHandler.handleTest(params));
                case "/active-editor" -> Response.json(
                        editor.handleActiveEditor(params));
                case "/open" -> Response.json(
                        editor.handleOpen(params));
                default -> Response.json(Json.error(
                        "Unknown path: " + path));
            };
        } catch (Exception e) {
            Log.error("Handler error on " + path, e);
            String msg = e.getMessage();
            return Response.json(Json.error(
                    msg != null ? msg : e.getClass().getSimpleName()));
        }
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(
                        pair.substring(0, eq), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(
                        pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, val);
            } else if (!pair.isBlank()) {
                params.put(URLDecoder.decode(
                        pair, StandardCharsets.UTF_8), "");
            }
        }
        return params;
    }

    private void sendError(Socket socket, int code, String message)
            throws IOException {
        String body = Json.error(message);
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + " " + message + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

}
