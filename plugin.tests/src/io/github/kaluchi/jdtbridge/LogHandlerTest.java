package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link LogHandler#parseEntries}. The parser
 * splits an Eclipse .log into !ENTRY / !MESSAGE / !STACK blocks;
 * malformed workspaces, non-ASCII timestamps, and multi-line
 * stack traces have to survive without crashing the endpoint.
 */
class LogHandlerTest {

    @Test
    void singleEntryParsesHeaderAndMessage() {
        String log = """
                !ENTRY io.github.kaluchi.jdtbridge 4 0 2026-04-18 23:33:59.744
                !MESSAGE Request error
                !STACK 0
                java.net.SocketException: Broken pipe
                \tat java.base/sun.nio.ch.SocketDispatcher.write0(Native Method)
                """;
        List<LogHandler.Entry> entries = LogHandler.parseEntries(log);
        assertEquals(1, entries.size());
        LogHandler.Entry entry = entries.get(0);
        assertEquals(4, entry.severity());
        assertEquals("io.github.kaluchi.jdtbridge", entry.bundle());
        assertEquals("2026-04-18 23:33:59.744", entry.timestamp());
        assertEquals("Request error", entry.message());
        assertTrue(entry.stack().contains("SocketException"));
        assertTrue(entry.stack().contains("SocketDispatcher.write0"));
    }

    @Test
    void multipleEntriesPreserveSourceOrder() {
        String log = """
                !ENTRY bundle.a 1 0 2026-04-18 23:33:59.001
                !MESSAGE first
                !ENTRY bundle.b 2 0 2026-04-18 23:33:59.002
                !MESSAGE second
                !ENTRY bundle.c 4 0 2026-04-18 23:33:59.003
                !MESSAGE third
                """;
        List<LogHandler.Entry> entries = LogHandler.parseEntries(log);
        assertEquals(3, entries.size());
        assertEquals("first",  entries.get(0).message());
        assertEquals("second", entries.get(1).message());
        assertEquals("third",  entries.get(2).message());
        assertEquals(1, entries.get(0).severity());
        assertEquals(2, entries.get(1).severity());
        assertEquals(4, entries.get(2).severity());
    }

    @Test
    void sessionAndSubentryLinesBleedIntoTheStackField() {
        String log = """
                !ENTRY bundle.x 4 0 2026-04-18 23:33:59.000
                !MESSAGE Compound
                !SESSION 2026-04-18 23:00:00.000 ---------------
                """;
        List<LogHandler.Entry> entries = LogHandler.parseEntries(log);
        assertEquals(1, entries.size());
        // !MESSAGE captures the entry's headline; !SESSION (and
        // any other non-MESSAGE prefix) falls through to the
        // stack capture so the original text stays retrievable.
        assertEquals("Compound", entries.get(0).message());
        assertTrue(entries.get(0).stack().contains("!SESSION"));
    }

    @Test
    void preambleBeforeFirstEntryIsDiscarded() {
        String log = """
                eclipse.buildId=4.40.0.20260409-0737
                java.version=21.0.10
                Framework arguments:  -product org.eclipse.epp.package.jee.product

                This is a continuation of log file ...
                !ENTRY bundle.x 1 0 2026-04-18 23:33:59.000
                !MESSAGE real entry
                """;
        List<LogHandler.Entry> entries = LogHandler.parseEntries(log);
        assertEquals(1, entries.size());
        assertEquals("real entry", entries.get(0).message());
    }

    @Test
    void emptyInputYieldsEmptyList() {
        assertEquals(0, LogHandler.parseEntries("").size());
    }

    @Test
    void malformedEntryLineIsTolerated() {
        // Entry header missing the code / timestamp fields — must
        // not throw; parser records what it can.
        String log = """
                !ENTRY only-bundle
                !MESSAGE partial
                """;
        List<LogHandler.Entry> entries = LogHandler.parseEntries(log);
        assertEquals(1, entries.size());
        assertEquals("only-bundle", entries.get(0).bundle());
        assertEquals("partial", entries.get(0).message());
    }

    @Test
    void entryWithoutMessageLineStillParsed() {
        String log = """
                !ENTRY bundle.x 1 0 2026-04-18 23:33:59.000
                """;
        List<LogHandler.Entry> entries = LogHandler.parseEntries(log);
        assertEquals(1, entries.size());
        assertEquals("", entries.get(0).message());
    }

    @Test
    void severityNameMapsToExpectedLabels() {
        String log = """
                !ENTRY bundle.x 1 0 ts
                !MESSAGE m
                !ENTRY bundle.x 2 0 ts
                !MESSAGE m
                !ENTRY bundle.x 4 0 ts
                !MESSAGE m
                !ENTRY bundle.x 8 0 ts
                !MESSAGE m
                !ENTRY bundle.x 99 0 ts
                !MESSAGE m
                """;
        List<LogHandler.Entry> entries = LogHandler.parseEntries(log);
        assertEquals(5, entries.size());
        assertTrue(entries.get(0).toJson().get("severity")
                .getAsString().equals("info"));
        assertTrue(entries.get(1).toJson().get("severity")
                .getAsString().equals("warning"));
        assertTrue(entries.get(2).toJson().get("severity")
                .getAsString().equals("error"));
        assertTrue(entries.get(3).toJson().get("severity")
                .getAsString().equals("cancel"));
        assertTrue(entries.get(4).toJson().get("severity")
                .getAsString().equals("unknown"));
    }
}
