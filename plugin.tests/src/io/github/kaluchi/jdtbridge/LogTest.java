package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Log} — the centralised Eclipse ILog wrapper.
 * Each test attaches an ILogListener to the bundle log and asserts
 * that the log call landed at the expected severity, message, and
 * throwable, then removes the listener so subsequent tests stay
 * clean.
 */
public class LogTest {

    private ILog log;
    private List<IStatus> captured;
    private ILogListener listener;

    @BeforeEach
    void setUp() {
        log = Platform.getLog(Log.class);
        captured = new ArrayList<>();
        listener = (status, pluginId) -> captured.add(status);
        log.addLogListener(listener);
    }

    @AfterEach
    void tearDown() {
        log.removeLogListener(listener);
    }

    @Nested
    class Info {

        @Test
        void emitsInfoSeverityAndMessage() {
            Log.info("info-test-" + System.nanoTime());
            IStatus s = lastForLevel(IStatus.INFO);
            assertNotNull(s, "Should record INFO status: " + captured);
            assertTrue(s.getMessage().startsWith("info-test-"));
            assertSame("io.github.kaluchi.jdtbridge",
                    s.getPlugin());
        }
    }

    @Nested
    class Warn {

        @Test
        void emitsWarningWithoutThrowable() {
            Log.warn("warn-noex-" + System.nanoTime());
            IStatus s = lastForLevel(IStatus.WARNING);
            assertNotNull(s);
            assertTrue(s.getMessage().startsWith("warn-noex-"));
            // Status without throwable has getException() == null.
            // (We cannot rely on === because the framework may copy.)
            org.junit.jupiter.api.Assertions
                    .assertNull(s.getException());
        }

        @Test
        void emitsWarningWithThrowable() {
            RuntimeException cause = new RuntimeException(
                    "warn-cause-" + System.nanoTime());
            Log.warn("warn-withex-" + System.nanoTime(), cause);
            IStatus s = lastForLevel(IStatus.WARNING);
            assertNotNull(s);
            assertSame(cause, s.getException());
        }
    }

    @Nested
    class Error {

        @Test
        void emitsErrorWithoutThrowable() {
            Log.error("err-noex-" + System.nanoTime());
            IStatus s = lastForLevel(IStatus.ERROR);
            assertNotNull(s);
            assertTrue(s.getMessage().startsWith("err-noex-"));
            org.junit.jupiter.api.Assertions
                    .assertNull(s.getException());
        }

        @Test
        void emitsErrorWithThrowable() {
            IllegalStateException cause = new IllegalStateException(
                    "err-cause-" + System.nanoTime());
            Log.error("err-withex-" + System.nanoTime(), cause);
            IStatus s = lastForLevel(IStatus.ERROR);
            assertNotNull(s);
            assertSame(cause, s.getException());
        }
    }

    private IStatus lastForLevel(int severity) {
        for (int i = captured.size() - 1; i >= 0; i--) {
            IStatus s = captured.get(i);
            if (s.getSeverity() == severity) return s;
        }
        return null;
    }
}
