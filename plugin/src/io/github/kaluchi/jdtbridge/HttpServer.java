package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonObject;

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
 * HTTP server on a raw ServerSocket.
 * Handles GET and POST requests, dispatches to handlers.
 * Supports configurable bind address and hot rebind.
 */
public class HttpServer {

    private final SearchHandler search = new SearchHandler();
    private final DiagnosticsHandler diagnostics =
            new DiagnosticsHandler();
    private final MavenHandler maven = new MavenHandler();
    private final RefactoringHandler refactoring =
            new RefactoringHandler();
    private final EditorHandler editor = new EditorHandler();
    private final LaunchTracker launchTracker = new LaunchTracker();
    private final LaunchHandler launch =
            new LaunchHandler(launchTracker);
    private final TestSessionHandler testSessionHandler =
            new TestSessionHandler();
    private final TestHandler testHandler =
            new TestHandler();
    private final ProjectHandler projectInfo = new ProjectHandler();
    private final SessionScope sessionScope = new SessionScope();
    private final RequestTracker requestTracker = new RequestTracker();
    private final ConfigService configService =
            new ConfigService(Activator.getHome());
    private final WelcomeHandler welcome =
            new WelcomeHandler(configService);
    private final ExecutorService executor =
            Executors.newCachedThreadPool(r -> {
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

        static Response html(String body) {
            return new Response("text/html", Map.of(), body);
        }
    }

    public void start() throws IOException {
        start(InetAddress.getLoopbackAddress(), 0);
    }

    public void start(InetAddress bindAddress, int port)
            throws IOException {
        launchTracker.start();
        serverSocket = bindWithFallback(bindAddress, port);
        running = true;
        startAcceptLoop(serverSocket);
    }

    /**
     * Rebind to a new address/port without restarting Eclipse.
     * In-flight requests on the old socket complete normally.
     */
    public void rebind(InetAddress bindAddress, int port)
            throws IOException {
        ServerSocket oldSocket = serverSocket;
        serverSocket = bindWithFallback(bindAddress, port);
        startAcceptLoop(serverSocket);
        if (oldSocket != null) {
            try { oldSocket.close(); } catch (IOException ignored) { }
        }
    }

    private void startAcceptLoop(ServerSocket listenSocket) {
        Thread acceptThread = new Thread(
                () -> acceptLoop(listenSocket), "jdtbridge-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** Returns the actual port the server is listening on. */
    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }

    /** Returns the bind address, or null if not started. */
    public InetAddress getBindAddress() {
        return serverSocket != null
                ? serverSocket.getInetAddress() : null;
    }

    private static ServerSocket bindWithFallback(
            InetAddress bindAddress, int port) throws IOException {
        try {
            return new ServerSocket(port, 50, bindAddress);
        } catch (IOException e) {
            if (port != 0) {
                Log.warn("Port " + port + " in use, auto-assigning");
                return new ServerSocket(0, 50, bindAddress);
            }
            throw e;
        }
    }

    public void setToken(String token) {
        this.token = token;
    }



    public void stop() {
        launchTracker.stop();
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

    private void acceptLoop(ServerSocket listenSocket) {
        while (running && !listenSocket.isClosed()) {
            try {
                Socket clientSocket = listenSocket.accept();
                executor.submit(() -> handle(clientSocket));
            } catch (IOException acceptException) {
                if (running && !listenSocket.isClosed()) {
                    Log.error("Accept error", acceptException);
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

            String method = parts[0];

            // Read headers
            String authHeader = null;
            String sessionHeader = null;
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null
                    && !line.isEmpty()) {
                if (line.regionMatches(true, 0,
                        "Authorization:", 0, 14)) {
                    authHeader = line.substring(14).trim();
                } else if (line.regionMatches(true, 0,
                        "Content-Length:", 0, 15)) {
                    contentLength = Integer.parseInt(
                            line.substring(15).trim());
                } else if (line.regionMatches(true, 0,
                        "X-Bridge-Session:", 0, 17)) {
                    sessionHeader = line.substring(17).trim();
                }
            }

            // Read POST body
            String body = null;
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = reader.read(buf, read,
                            contentLength - read);
                    if (n < 0) break;
                    read += n;
                }
                body = new String(buf, 0, read);
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

            // Status endpoints — no auth required (loopback only)
            if (path.startsWith("/status")) {
                Response resp = dispatchStatus(path, method, body);
                sendResponse(socket, resp);
                return;
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

            // Streaming endpoints — bypass dispatch, own socket
            if ("/launch/console/stream".equals(path)) {
                handleConsoleStream(socket, params);
                return;
            }
            if ("/test/status/stream".equals(path)) {
                handleTestStatusStream(socket, params);
                return;
            }

            // Telemetry — POST enqueues, GET drains
            if ("/telemetry".equals(path)) {
                if ("POST".equals(method) && body != null
                        && sessionHeader != null) {
                    requestTracker.logTelemetry(sessionHeader, body);
                    sendResponse(socket,
                            Response.json("{\"ok\":true}"));
                } else {
                    String sess = params.get("session");
                    String text = sess != null
                            ? requestTracker.drain(sess) : "";
                    sendResponse(socket,
                            Response.text(text, Map.of()));
                }
                return;
            }

            ProjectScope scope = sessionScope.resolve(
                    sessionHeader);

            long startNs = System.nanoTime();
            Response resp = dispatch(path, params, body, scope);
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            sendResponse(socket, resp);

            requestTracker.logRequest(sessionHeader, method, path,
                    200, durationMs);
        } catch (Exception e) {
            Log.error("Request error", e);
        }
    }

    /**
     * Streaming console — bypasses dispatch, writes directly to
     * socket. HTTP protocol only; business logic in ConsoleStreamer.
     */
    private void handleConsoleStream(Socket socket,
            Map<String, String> params) {
        String name = params.get("launchId");
        if (name == null || name.isBlank()) {
            try { sendError(socket, 400, "Missing launchId"); }
            catch (IOException e) { /* ignore */ }
            return;
        }

        LaunchTracker.TrackedLaunch tl = awaitLaunch(name);
        if (tl == null) {
            try {
                sendError(socket, 404,
                        "Launch not found: " + name);
            } catch (IOException e) { /* ignore */ }
            return;
        }

        String stream = params.get("stream");
        int tail = -1;
        String tailStr = params.get("tail");
        if (tailStr != null) {
            try { tail = Integer.parseInt(tailStr); }
            catch (NumberFormatException e) { /* full */ }
        }

        try {
            socket.setSoTimeout(0);
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Connection: close\r\n"
                    + "Cache-Control: no-cache\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));

            ConsoleStreamer.stream(tl, out, stream, tail);
        } catch (IOException
                | ConsoleStreamer.StreamClosedException e) {
            // Client disconnected — normal for Ctrl+C
        }
    }

    /**
     * Streaming test status — bypasses dispatch, writes directly
     * to socket. JSONL format, one event per line.
     */
    private void handleTestStatusStream(Socket socket,
            Map<String, String> params) {
        String testRunId = params.get("testRunId");
        if (testRunId == null || testRunId.isBlank()) {
            try { sendError(socket, 400, "Missing testRunId"); }
            catch (IOException e) { /* ignore */ }
            return;
        }

        var session = testSessionHandler.findSession(testRunId);
        if (session == null) {
            // Session may not exist yet — wait briefly
            for (int i = 0; i < 20 && session == null; i++) {
                try { Thread.sleep(500); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                session = testSessionHandler.findSession(
                        testRunId);
            }
        }
        if (session == null) {
            try {
                sendError(socket, 404,
                        "Test run not found: " + testRunId);
            } catch (IOException e) { /* ignore */ }
            return;
        }

        String filter = params.get("filter");

        try {
            socket.setSoTimeout(0);
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/x-ndjson;"
                    + " charset=utf-8\r\n"
                    + "Connection: close\r\n"
                    + "Cache-Control: no-cache\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));

            TestProgressStreamer.stream(session, out, filter);
        } catch (IOException
                | TestProgressStreamer.StreamClosedException e) {
            // Client disconnected — normal for Ctrl+C
        }
    }

    /** Wait briefly for a launch to appear in the tracker. */
    private LaunchTracker.TrackedLaunch awaitLaunch(String name) {
        for (int i = 0; i < 10; i++) {
            LaunchTracker.TrackedLaunch tl =
                    launchTracker.get(name);
            if (tl != null) return tl;
            try { Thread.sleep(500); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
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

    private Response dispatchStatus(String path, String method,
            String body) {
        try {
            if ("/status".equals(path) && "GET".equals(method)) {
                return welcome.handleStatus();
            }
            if ("/status/dismiss".equals(path)
                    && "POST".equals(method)) {
                return welcome.handleDismiss();
            }
            if ("/status/undismiss".equals(path)
                    && "POST".equals(method)) {
                return welcome.handleUndismiss();
            }
            return Response.json(jsonError("Not found: " + path));
        } catch (Exception e) {
            Log.error("Status handler error", e);
            return Response.json(jsonError(e.getMessage()));
        }
    }

    private Response dispatch(String path,
            Map<String, String> params, String requestBody,
            ProjectScope scope) {
        try {
            return switch (path) {
                case "/projects" -> Response.json(
                        search.handleProjects(scope));
                case "/project-info" -> Response.json(
                        projectInfo.handleProjectInfo(params));
                case "/find" -> Response.json(
                        search.handleFind(params, scope));
                case "/references" -> Response.json(
                        search.handleReferences(params, scope));
                case "/implementors" -> Response.json(
                        search.handleImplementors(params, scope));
                case "/hierarchy" -> Response.json(
                        search.handleHierarchy(params, scope));
                case "/problems" -> Response.json(
                        diagnostics.handleProblems(params, scope));
                case "/build" -> Response.json(
                        diagnostics.handleBuild(params));
                case "/refresh" -> Response.json(
                        diagnostics.handleRefresh(params));
                case "/maven/update" -> Response.json(
                        maven.handleUpdate(params));
                case "/type-info" -> Response.json(
                        search.handleTypeInfo(params));
                case "/source" -> search.handleSource(params,
                        scope);
                case "/organize-imports" -> Response.json(
                        refactoring.handleOrganizeImports(params));
                case "/format" -> Response.json(
                        refactoring.handleFormat(params));
                case "/rename" -> Response.json(
                        refactoring.handleRename(params));
                case "/move" -> Response.json(
                        refactoring.handleMove(params));
                case "/test/run" -> Response.json(
                        testHandler.handleTestRun(params));
                case "/test/status" -> Response.json(
                        testSessionHandler.handleStatus(params));
                case "/test/sessions" -> Response.json(
                        testSessionHandler.handleSessions(
                                params, scope));
                case "/test/clear" -> Response.json(
                        testSessionHandler.handleClear(params));
                case "/editors" -> Response.json(
                        editor.handleEditors(params, scope));
                case "/open" -> Response.json(
                        editor.handleOpen(params));
                case "/launch/list" -> Response.json(
                        launch.handleList(params, scope));
                case "/launch/configs" -> Response.json(
                        launch.handleConfigs(params, scope));
                case "/launch/config" -> Response.json(
                        launch.handleConfig(params));
                case "/launch/config/delete" -> Response.json(
                        launch.handleConfigDelete(params));
                case "/launch/import" -> Response.json(
                        launch.handleImport(params, requestBody));
                case "/launch/clear" -> Response.json(
                        launch.handleClear(params));
                case "/launch/console" -> Response.json(
                        launch.handleConsole(params));
                case "/launch/run" -> Response.json(
                        launch.handleRun(params));
                case "/launch/stop" -> Response.json(
                        launch.handleStop(params));
                default -> Response.json(jsonError(
                        "Unknown path: " + path));
            };
        } catch (Exception e) {
            Log.error("Handler error on " + path, e);
            String msg = e.getMessage();
            return Response.json(jsonError(
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

    /** Build {"error":"message"} JSON string. */
    static String jsonError(String message) {
        var obj = new JsonObject();
        obj.addProperty("error", message);
        return obj.toString();
    }

    private void sendError(Socket socket, int code, String message)
            throws IOException {
        String body = jsonError(message);
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
