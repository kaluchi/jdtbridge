package io.github.kaluchi.jdtbridge;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

/**
 * Centralized logging via Eclipse ILog.
 * Messages appear in Error Log view and {@code <workspace>/.metadata/.log}.
 * Falls back to stderr when running outside OSGi (e.g. plain JUnit).
 */
class Log {

    private static final String BUNDLE_ID =
            "io.github.kaluchi.jdtbridge";
    private static final ILog LOGGER = initLogger();

    private static ILog initLogger() {
        try {
            return Platform.getLog(Log.class);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    static void info(String msg) {
        log(IStatus.INFO, msg, null);
    }

    static void warn(String msg) {
        log(IStatus.WARNING, msg, null);
    }

    static void warn(String msg, Throwable t) {
        log(IStatus.WARNING, msg, t);
    }

    static void error(String msg) {
        log(IStatus.ERROR, msg, null);
    }

    static void error(String msg, Throwable t) {
        log(IStatus.ERROR, msg, t);
    }

    private static void log(int severity, String msg,
            Throwable t) {
        if (LOGGER != null) {
            LOGGER.log(new Status(severity, BUNDLE_ID, msg, t));
        } else {
            String level = severity == IStatus.ERROR ? "ERROR"
                    : severity == IStatus.WARNING ? "WARN"
                    : "INFO";
            System.err.println("[" + level + "] " + BUNDLE_ID
                    + ": " + msg);
            if (t != null) t.printStackTrace(System.err);
        }
    }
}
