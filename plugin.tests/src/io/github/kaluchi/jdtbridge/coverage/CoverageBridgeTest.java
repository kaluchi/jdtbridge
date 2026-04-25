package io.github.kaluchi.jdtbridge.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.kaluchi.jdtbridge.ProjectScope;

/**
 * Tests for {@link CoverageBridge} — the guarded entry point for
 * {@code /coverage/*} routes. Covers both the EclEmma-present
 * dispatch path and the EclEmma-absent error JSON.
 */
public class CoverageBridgeTest {

    @Nested
    class NotInstalledError {

        @Test
        void hasErrorKind() {
            String json = CoverageBridge.notInstalledError();
            var obj = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("coverage-not-installed",
                    obj.get("error").getAsString());
        }

        @Test
        void includesBundleId() {
            String json = CoverageBridge.notInstalledError();
            assertTrue(json.contains("org.eclipse.eclemma.core"),
                    "Should mention EclEmma bundle: " + json);
        }

        @Test
        void mentionsEclipseInstance() {
            String json = CoverageBridge.notInstalledError();
            assertTrue(json.contains("Eclipse"),
                    "Should mention Eclipse instance: " + json);
        }
    }

    @Nested
    class StreamNotInstalledEvent {

        @Test
        void usesEventFailedShape() {
            String json = CoverageBridge.streamNotInstalledEvent();
            var obj = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("failed",
                    obj.get("event").getAsString());
            assertEquals("coverage-not-installed",
                    obj.get("reason").getAsString());
        }

        @Test
        void doesNotUseDispatchErrorShape() {
            // Dispatch errors use {"error":"...","message":"..."};
            // stream events use {"event":"failed","reason":"..."}.
            // Stream consumers parse by event field — the dispatch
            // shape would be invisible to them.
            String json = CoverageBridge.streamNotInstalledEvent();
            var obj = JsonParser.parseString(json).getAsJsonObject();
            assertTrue(obj.has("event"),
                    "Stream error must carry event field: " + json);
            assertTrue(!obj.has("error"),
                    "Stream error must NOT use dispatch shape: "
                            + json);
        }
    }

    @Nested
    class IsAvailable {

        // The test runtime imports EclEmma as resolution:=optional;
        // when running under the user's local Eclipse target,
        // EclEmma is present, so isAvailable() returns true.
        // The CI target also pulls in EclEmma; absence is only a
        // production scenario when a user installs the bridge into
        // an Eclipse without EclEmma.
        @Test
        void returnsBooleanWithoutCrashing() {
            // No assertion on the value — both true and false are
            // legitimate outcomes depending on the target. Just
            // verify the call is safe.
            CoverageBridge.isAvailable();
        }
    }

    @Nested
    class Dispatch {

        private final CoverageBridge bridge = new CoverageBridge();

        @Test
        void unknownPathReturnsCoverageUnknownPath() {
            String json = bridge.dispatch("/coverage/nope",
                    Map.of(), null, ProjectScope.ALL);
            // Either the bridge returns "not-installed" (EclEmma
            // missing) or the router returns "coverage-unknown-path".
            // Both are valid; we just assert one of them.
            assertTrue(json.contains("coverage-not-installed")
                            || json.contains("coverage-unknown-path"),
                    "Unexpected error kind: " + json);
        }

        @Test
        void knownPathReturnsValidJson() {
            String json = bridge.dispatch("/coverage/runs",
                    Map.of(), null, ProjectScope.ALL);
            // Phase 1: stub returns coverage-not-implemented;
            // when EclEmma absent: coverage-not-installed. Both
            // produce parseable JSON.
            var parsed = JsonParser.parseString(json);
            assertFalse(parsed.isJsonNull(),
                    "Should produce non-null JSON: " + json);
        }

        @Test
        void everyKnownPathRoutes() {
            String[] paths = {
                    "/coverage/run", "/coverage/dump",
                    "/coverage/refresh", "/coverage/relaunch",
                    "/coverage/runs", "/coverage/session",
                    "/coverage/active", "/coverage/activate",
                    "/coverage/merge", "/coverage/remove"
            };
            for (String path : paths) {
                String json = bridge.dispatch(path, Map.of(), null,
                        ProjectScope.ALL);
                JsonParser.parseString(json);
            }
        }
    }
}
