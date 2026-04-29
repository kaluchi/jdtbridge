package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests the HTTP server layer: real TCP connections, auth, routing.
 */
public class HttpIntegrationTest {

    private static HttpServer server;
    private static int port;
    private static final String TOKEN = "test-secret-token-42";

    @BeforeAll
    public static void setUp() throws Exception {
        TestFixture.create();
        server = new HttpServer();
        server.setToken(TOKEN);
        server.start();
        port = server.getPort();
        assertTrue(port > 0, "Port should be assigned");
    }

    @AfterAll
    public static void tearDown() throws Exception {
        if (server != null) server.stop();
        TestFixture.destroy();
    }

    // ---- Auth ----

    @Test
    public void noTokenReturns401() throws Exception {
        String response = rawRequest("GET /projects HTTP/1.1",
                "Host: localhost");
        assertTrue(response.startsWith("HTTP/1.1 401"),
                "Should be 401: " + response);
    }

    @Test
    public void wrongTokenReturns401() throws Exception {
        String response = rawRequest("GET /projects HTTP/1.1",
                "Host: localhost",
                "Authorization: Bearer wrong-token");
        assertTrue(response.startsWith("HTTP/1.1 401"),
                "Should be 401: " + response);
    }

    @Test
    public void correctTokenReturns200() throws Exception {
        String response = rawRequest("GET /projects HTTP/1.1",
                "Host: localhost",
                "Authorization: Bearer " + TOKEN);
        assertTrue(response.startsWith("HTTP/1.1 200"),
                "Should be 200: " + response);
    }

    @Test
    public void basicSchemeReturns401() throws Exception {
        String response = rawRequest("GET /projects HTTP/1.1",
                "Host: localhost",
                "Authorization: Basic dXNlcjpwYXNz");
        assertTrue(response.startsWith("HTTP/1.1 401"),
                "Basic auth should be rejected: " + response);
    }

    @Test
    public void bearerWithExtraSpacesReturns401() throws Exception {
        String response = rawRequest("GET /projects HTTP/1.1",
                "Host: localhost",
                "Authorization:  Bearer  " + TOKEN);
        // Extra spaces around Bearer — should fail (strict match)
        assertTrue(response.startsWith("HTTP/1.1 401"),
                "Malformed Bearer should be 401: " + response);
    }

    @Test
    public void emptyAuthHeaderReturns401() throws Exception {
        String response = rawRequest("GET /projects HTTP/1.1",
                "Host: localhost",
                "Authorization: ");
        assertTrue(response.startsWith("HTTP/1.1 401"),
                "Empty auth should be 401: " + response);
    }

    @Test
    public void authHeaderCaseInsensitive() throws Exception {
        // HTTP header names are case-insensitive (RFC 7230)
        String response = rawRequest("GET /projects HTTP/1.1",
                "Host: localhost",
                "authorization: Bearer " + TOKEN);
        assertTrue(response.startsWith("HTTP/1.1 200"),
                "Lowercase header should work: " + response);
    }

    @Test
    public void serverWithoutTokenAllowsAll() throws Exception {
        // Start a second server without token
        HttpServer openServer = new HttpServer();
        openServer.start();
        int openPort = openServer.getPort();
        try {
            try (Socket socket = new Socket("localhost", openPort)) {
                socket.setSoTimeout(15_000);
                OutputStream out = socket.getOutputStream();
                out.write("GET /projects HTTP/1.1\r\nHost: localhost\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(),
                                StandardCharsets.UTF_8));
                String firstLine = reader.readLine();
                assertTrue(firstLine.startsWith("HTTP/1.1 200"),
                        "No-token server should allow: " + firstLine);
            }
        } finally {
            openServer.stop();
        }
    }

    @Test
    public void error401BodyIsJson() throws Exception {
        String response = rawRequest("GET /projects HTTP/1.1",
                "Host: localhost");
        assertTrue(response.contains("{\"error\":\"Unauthorized\"}"),
                "401 body should be JSON: " + response);
    }

    // ---- Routing ----

    @Test
    public void projectsEndpoint() throws Exception {
        String body = authedGet("/projects");
        assertTrue(body.startsWith("["),
                "Should be JSON array: " + body);
        assertTrue(body.contains(TestFixture.PROJECT_NAME),
                "Should include test project: " + body);
    }

    @Test
    public void typesEndpoint() throws Exception {
        String body = authedGet("/types?pattern=Dog&sourceOnly");
        assertTrue(body.contains("test.model.Dog"),
                "Should find Dog: " + body);
    }

    @Test
    public void unknownPathReturnsError() throws Exception {
        String body = authedGet("/nonexistent");
        assertTrue(body.contains("Unknown path"),
                "Should be error: " + body);
    }

    @Test
    public void problemsEndpoint() throws Exception {
        String body = authedGet("/problems?of="
                + TestFixture.PROJECT_NAME);
        assertTrue(body.contains("BrokenClass"),
                "Should contain BrokenClass error: " + body);
    }

    @Test
    public void typeEndpoint() throws Exception {
        String body = authedGet("/type?of=test.model.Animal");
        assertTrue(body.contains("\"typeKind\":\"interface\""),
                "Should be interface: " + body);
    }

    @Test
    public void sourceEndpoint() throws Exception {
        String response = rawRequest("GET /source?of=test.model.Dog HTTP/1.1",
                "Host: localhost",
                "Authorization: Bearer " + TOKEN);
        assertTrue(response.startsWith("HTTP/1.1 200"),
                "Should be 200: " + response);
        assertTrue(response.contains("application/json"),
                "Should be JSON: " + response);
        assertTrue(response.contains("public class Dog"),
                "Should have source in body: " + response);
    }

    @Test
    public void queryParamEncoding() throws Exception {
        // URL-encoded class name
        String body = authedGet(
                "/type?of=test.model.Dog");
        assertTrue(body.contains("test.model.Dog"),
                "Should work with dots: " + body);
    }

    // ---- Helpers ----

    private String authedGet(String path) throws Exception {
        String response = rawRequest("GET " + path + " HTTP/1.1",
                "Host: localhost",
                "Authorization: Bearer " + TOKEN);
        int bodyStart = response.indexOf("\r\n\r\n");
        if (bodyStart < 0) {
            throw new AssertionError(
                    "HTTP response missing header/body separator: "
                            + response);
        }
        return response.substring(bodyStart + 4);
    }

    // ---- Status (no auth) ----

    @Test
    public void statusEndpointNoAuth() throws Exception {
        String response = rawRequest("GET /status HTTP/1.1",
                "Host: localhost");
        assertTrue(response.startsWith("HTTP/1.1 200"),
                "/status should not require auth: " + response);
        assertTrue(response.contains("text/html"),
                "Should be HTML: " + response);
    }

    @Test
    public void statusDismissPost() throws Exception {
        String response = rawRequest("POST /status/dismiss HTTP/1.1",
                "Host: localhost",
                "Content-Length: 0");
        assertTrue(response.startsWith("HTTP/1.1 200"),
                "dismiss POST: " + response);
        assertTrue(response.contains("\"ok\":true"),
                "Should return ok: " + response);
    }

    // ---- POST with body ----

    @Test
    public void postBodyParsedCorrectly() throws Exception {
        String body = "{\"configId\":\"http-test-import\"}";
        String response = rawRequestWithBody(
                "POST /launch/import HTTP/1.1", body,
                "Host: localhost",
                "Authorization: Bearer " + TOKEN);
        assertTrue(response.startsWith("HTTP/1.1 200"),
                "POST with body: " + response);
    }

    // ---- Session header ----

    @Test
    public void sessionHeaderDoesNotBreakRequest() throws Exception {
        String response = rawRequest("GET /projects HTTP/1.1",
                "Host: localhost",
                "Authorization: Bearer " + TOKEN,
                "X-Bridge-Session: test-session-abc");
        assertTrue(response.startsWith("HTTP/1.1 200"),
                "Session header should be accepted: " + response);
    }

    // ---- Telemetry ----

    @Test
    public void telemetryPostReturnsOk() throws Exception {
        String body = "{\"event\":\"test\"}";
        String response = rawRequestWithBody(
                "POST /telemetry HTTP/1.1", body,
                "Host: localhost",
                "Authorization: Bearer " + TOKEN,
                "X-Bridge-Session: tel-session");
        assertTrue(response.startsWith("HTTP/1.1 200"),
                "telemetry POST: " + response);
        assertTrue(response.contains("\"ok\":true"),
                "telemetry ok: " + response);
    }

    @Test
    public void telemetryGetDrains() throws Exception {
        String response = rawRequest(
                "GET /telemetry?session=tel-session HTTP/1.1",
                "Host: localhost",
                "Authorization: Bearer " + TOKEN);
        assertTrue(response.startsWith("HTTP/1.1 200"),
                "telemetry GET: " + response);
    }

    // ---- Streaming endpoint error paths ----

    @Test
    public void consoleStreamMissingLaunchIdReturns400()
            throws Exception {
        String response = rawRequest(
                "GET /launch/console/stream HTTP/1.1",
                "Host: localhost",
                "Authorization: Bearer " + TOKEN);
        assertTrue(response.contains("400"),
                "missing launchId → 400: " + response);
    }

    @Test
    public void consoleStreamUnknownLaunchReturns404()
            throws Exception {
        String response = rawRequest(
                "GET /launch/console/stream?launchId=no-such HTTP/1.1",
                "Host: localhost",
                "Authorization: Bearer " + TOKEN);
        assertTrue(response.contains("404"),
                "unknown launch → 404: " + response);
    }

    @Test
    public void testStatusStreamMissingIdReturns400()
            throws Exception {
        String response = rawRequest(
                "GET /test/status/stream HTTP/1.1",
                "Host: localhost",
                "Authorization: Bearer " + TOKEN);
        assertTrue(response.contains("400"),
                "missing testRunId → 400: " + response);
    }

    @Test
    public void testStatusStreamUnknownIdReturns404()
            throws Exception {
        String response = rawRequest(
                "GET /test/status/stream?testRunId=no-such HTTP/1.1",
                "Host: localhost",
                "Authorization: Bearer " + TOKEN);
        assertTrue(response.contains("404"),
                "unknown testRunId → 404: " + response);
    }

    @Test
    public void coverageStreamMissingIdReturns400()
            throws Exception {
        String response = rawRequest(
                "GET /coverage/session/stream HTTP/1.1",
                "Host: localhost",
                "Authorization: Bearer " + TOKEN);
        assertTrue(response.contains("400"),
                "missing coverageId → 400: " + response);
    }

    @Test
    public void coverageStreamUnknownIdReturnsFailedEvent()
            throws Exception {
        String response = rawRequest(
                "GET /coverage/session/stream?coverageId=no-such HTTP/1.1",
                "Host: localhost",
                "Authorization: Bearer " + TOKEN);
        assertTrue(response.contains("coverage-not-found"),
                "unknown coverageId → failed event: " + response);
    }

    // ---- Helpers ----

    private String rawRequestWithBody(String requestLine,
            String body, String... headers) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            byte[] bodyBytes =
                    body.getBytes(StandardCharsets.UTF_8);
            StringBuilder req = new StringBuilder();
            req.append(requestLine).append("\r\n");
            for (String h : headers) {
                req.append(h).append("\r\n");
            }
            req.append("Content-Length: ")
                    .append(bodyBytes.length).append("\r\n");
            req.append("\r\n");
            out.write(req.toString()
                    .getBytes(StandardCharsets.UTF_8));
            out.write(bodyBytes);
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\r\n");
            }
            return response.toString();
        }
    }

    private String rawRequest(String requestLine, String... headers)
            throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            StringBuilder req = new StringBuilder();
            req.append(requestLine).append("\r\n");
            for (String h : headers) {
                req.append(h).append("\r\n");
            }
            req.append("\r\n");
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\r\n");
            }
            return response.toString();
        }
    }
}
