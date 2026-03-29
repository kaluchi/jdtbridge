package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for WelcomeHandler — dismiss/undismiss, status page.
 */
public class WelcomeHandlerTest {

    @TempDir
    Path tempDir;
    ConfigService config;
    WelcomeHandler handler;

    @BeforeEach
    void setUp() {
        config = new ConfigService(tempDir);
        handler = new WelcomeHandler(config);
    }

    @Nested
    class IsDismissed {

        @Test
        void falseByDefault() {
            assertFalse(handler.isDismissed());
        }

        @Test
        void trueAfterDismiss() throws IOException {
            config.putBoolean("welcomeDismissed", true);
            assertTrue(handler.isDismissed());
        }

        @Test
        void falseAfterUndismiss() throws IOException {
            config.putBoolean("welcomeDismissed", true);
            config.putBoolean("welcomeDismissed", false);
            assertFalse(handler.isDismissed());
        }
    }

    @Nested
    class HandleDismiss {

        @Test
        void setsConfigAndReturnsOk() {
            HttpServer.Response resp = handler.handleDismiss();
            assertEquals("application/json", resp.contentType());
            assertTrue(resp.body().contains("\"ok\":true"));
            assertTrue(handler.isDismissed());
        }

        @Test
        void idempotent() {
            handler.handleDismiss();
            HttpServer.Response resp = handler.handleDismiss();
            assertTrue(resp.body().contains("\"ok\":true"));
            assertTrue(handler.isDismissed());
        }
    }

    @Nested
    class HandleUndismiss {

        @Test
        void clearsConfigAndReturnsOk() {
            handler.handleDismiss();
            HttpServer.Response resp = handler.handleUndismiss();
            assertEquals("application/json", resp.contentType());
            assertTrue(resp.body().contains("\"ok\":true"));
            assertFalse(handler.isDismissed());
        }

        @Test
        void noOpWhenAlreadyUndismissed() {
            HttpServer.Response resp = handler.handleUndismiss();
            assertTrue(resp.body().contains("\"ok\":true"));
            assertFalse(handler.isDismissed());
        }
    }

    @Nested
    class HandleStatus {

        @Test
        void returnsHtmlWithPlaceholdersReplaced()
                throws IOException {
            HttpServer.Response resp = handler.handleStatus();
            assertEquals("text/html", resp.contentType());
            // Placeholders should be replaced, not present raw
            assertFalse(resp.body().contains("{{version}}"));
            assertFalse(resp.body().contains("{{port}}"));
            assertFalse(resp.body().contains("{{cliInstalled}}"));
            assertFalse(resp.body().contains("{{dismissed}}"));
        }

        @Test
        void dismissedStateReflectedInHtml()
                throws IOException {
            handler.handleDismiss();
            HttpServer.Response resp = handler.handleStatus();
            assertTrue(resp.body().contains("true"),
                    "dismissed=true should appear in page");
        }
    }

    @Nested
    class IsCliInstalled {

        @Test
        void returnsBoolean() {
            // detectCli() spawns npm — result depends on
            // environment but must not throw
            boolean result = handler.isCliInstalled();
            // Just verify it completes without exception;
            // actual value depends on whether jdt CLI is
            // installed globally
            assertEquals(result, handler.isCliInstalled(),
                    "Should be deterministic across calls");
        }
    }
}
