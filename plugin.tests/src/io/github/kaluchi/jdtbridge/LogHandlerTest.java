package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @TempDir
    Path tmp;

    @Test
    void readTailBytesReadsEntireFileWhenSmallerThanCap()
            throws Exception {
        Path p = tmp.resolve("small.log");
        Files.writeString(p, "!ENTRY a 1 0 t\n!MESSAGE m\n",
                StandardCharsets.UTF_8);
        String content = LogHandler.readTailBytes(p, 1024);
        assertEquals("!ENTRY a 1 0 t\n!MESSAGE m\n", content);
    }

    @Test
    void readTailBytesAlignsToNextNewlineWhenTruncated()
            throws Exception {
        Path p = tmp.resolve("big.log");
        // Head garbage that will be dropped; tail entry survives.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("garbage line ").append(i).append('\n');
        }
        sb.append("!ENTRY b 4 0 t\n!MESSAGE tail\n");
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);

        // Cap below the total size forces a truncate-and-align path.
        String tail = LogHandler.readTailBytes(p, 256);
        assertTrue(tail.length() <= 256);
        assertTrue(tail.contains("!ENTRY b 4 0 t"),
                "aligned tail must contain a full !ENTRY header, "
                + "not a mid-line suffix");
        // First character is immediately after a '\n' alignment.
        List<LogHandler.Entry> entries = LogHandler.parseEntries(tail);
        assertEquals(1, entries.size());
        assertEquals("tail", entries.get(0).message());
    }

    @Test
    void readTailBytesReturnsEmptyWhenNoNewlineInWindow()
            throws Exception {
        // Pathological case: the whole file is one unterminated
        // blob (binary garbage, a crashed writer, …). The tail
        // window contains no '\n', so alignment walks off the end.
        // parseEntries must see an empty String, not NPE or loop.
        Path p = tmp.resolve("blob.log");
        byte[] blob = new byte[1024];
        java.util.Arrays.fill(blob, (byte) 'x');
        Files.write(p, blob);
        String tail = LogHandler.readTailBytes(p, 128);
        assertEquals("", tail);
        assertEquals(0, LogHandler.parseEntries(tail).size());
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
