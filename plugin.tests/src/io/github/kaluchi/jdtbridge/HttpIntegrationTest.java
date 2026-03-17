package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Tests the HTTP server layer: real TCP connections, auth, routing.
 */
@EnabledIfSystemProperty(named = "jdtbridge.integration-tests", matches = "true")
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
                socket.setSoTimeout(5000);
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
    public void findEndpoint() throws Exception {
        String body = authedGet("/find?name=Dog&source");
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
    public void errorsEndpoint() throws Exception {
        String body = authedGet("/errors?project="
                + TestFixture.PROJECT_NAME + "&no-refresh");
        assertTrue(body.contains("BrokenClass"),
                "Should contain BrokenClass error: " + body);
    }

    @Test
    public void typeInfoEndpoint() throws Exception {
        String body = authedGet("/type-info?class=test.model.Animal");
        assertTrue(body.contains("\"kind\":\"interface\""),
                "Should be interface: " + body);
    }

    @Test
    public void sourceEndpoint() throws Exception {
        String response = rawRequest("GET /source?class=test.model.Dog HTTP/1.1",
                "Host: localhost",
                "Authorization: Bearer " + TOKEN);
        assertTrue(response.startsWith("HTTP/1.1 200"),
                "Should be 200: " + response);
        assertTrue(response.contains("X-File:"),
                "Should have X-File header: " + response);
        assertTrue(response.contains("public class Dog"),
                "Should have source body: " + response);
    }

    @Test
    public void queryParamEncoding() throws Exception {
        // URL-encoded class name
        String body = authedGet(
                "/type-info?class=test.model.Dog");
        assertTrue(body.contains("test.model.Dog"),
                "Should work with dots: " + body);
    }

    // ---- Helpers ----

    private String authedGet(String path) throws Exception {
        String response = rawRequest("GET " + path + " HTTP/1.1",
                "Host: localhost",
                "Authorization: Bearer " + TOKEN);
        // Extract body (after blank line)
        int bodyStart = response.indexOf("\r\n\r\n");
        if (bodyStart >= 0) {
            return response.substring(bodyStart + 4);
        }
        return response;
    }

    private String rawRequest(String requestLine, String... headers)
            throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
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
