package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

/**
 * Unit tests for the package-private wire-format helpers exposed
 * by {@link HttpServer}: {@link HttpServer#jsonError(String)} and
 * {@link HttpServer#missingParamError(String)}. The HTTP server
 * lifecycle itself is exercised by HttpServerBindTest /
 * HttpServerDisconnectTest / HttpIntegrationTest — these tests
 * stay focused on the small data-shape surface.
 */
public class HttpServerHelpersTest {

    @Test
    void jsonErrorWrapsMessage() {
        String json = HttpServer.jsonError("boom");
        var obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("boom", obj.get("error").getAsString());
        assertFalse(obj.has("message"),
                "jsonError emits a flat envelope without 'message': "
                        + json);
    }

    @Test
    void missingParamErrorMentionsParamName() {
        String json = HttpServer.missingParamError("configId");
        var obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("Missing 'configId' parameter",
                obj.get("error").getAsString());
    }

    @Test
    void missingParamErrorIsDistinctPerName() {
        // Two distinct param names yield two distinct error texts.
        assertFalse(HttpServer.missingParamError("a")
                .equals(HttpServer.missingParamError("b")));
    }

    @Test
    void missingParamErrorPreservesNamePunctuation() {
        // Param name is interpolated verbatim — caller controls
        // the wire spelling exactly.
        String json = HttpServer.missingParamError("test-run-id");
        var obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("Missing 'test-run-id' parameter",
                obj.get("error").getAsString());
    }
}
