// CI gate for the :jdt/graph module's documented examples.
//
// Every `:snippet "..."` inside graph.qlang must parse cleanly as a
// qlang pipeline. A reviewer touching an operand descriptor that
// breaks its own docs sees the failure in the CLI test run rather
// than as a surprise report from the user months later.
//
// Semantic rot (stale FQN referenced by a snippet — e.g. the
// `org.eclipse.core.runtime.IStartup` → `org.eclipse.ui.IStartup`
// drift caught during the 0.2.x → 0.3.0 review) lives outside this
// gate: those are only detectable against a live Eclipse via
// `jdt q 'reify(:@name) | runExamples'`. What this gate does cover
// is pure-syntax rot — a typo in a combinator, an unterminated
// String, a mismatched bracket — none of which need Eclipse to
// verify.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import { describe, it, expect } from 'vitest';
import { parse } from '@kaluchi/qlang-core';

const __dirname = dirname(fileURLToPath(import.meta.url));
const GRAPH_QLANG = resolve(__dirname, '..', 'lib', 'jdt', 'graph.qlang');

// Match :snippet "<string-with-qlang-escapes>". The escape set
// matches qlang's \\, \", \n, \t, \r — same as qlang's parser.
// Non-greedy capture stays inside the first closing quote that is
// not part of an escape sequence.
const SNIPPET_RE = /:snippet\s+"((?:\\.|[^"\\])*)"/g;

function extractSnippets(source) {
    const snippets = [];
    for (const match of source.matchAll(SNIPPET_RE)) {
        snippets.push(unescapeQlangString(match[1]));
    }
    return snippets;
}

function unescapeQlangString(raw) {
    return raw
        .replace(/\\n/g, '\n')
        .replace(/\\t/g, '\t')
        .replace(/\\r/g, '\r')
        .replace(/\\"/g, '"')
        .replace(/\\\\/g, '\\');
}

describe(':jdt/graph examples parse as qlang', () => {
    const graphSource = readFileSync(GRAPH_QLANG, 'utf8');
    const snippets = extractSnippets(graphSource);

    it('catalog lists every declared :examples snippet', () => {
        expect(snippets.length).toBeGreaterThan(0);
        // Sanity: the count matches the raw :snippet occurrences.
        const snippetOccurrences =
            (graphSource.match(/:snippet\s+"/g) ?? []).length;
        expect(snippets.length).toBe(snippetOccurrences);
    });

    for (const snippet of snippets) {
        const label = snippet.length > 60
            ? snippet.slice(0, 57) + '…' : snippet;
        it(`parses: ${label}`, () => {
            expect(() => parse(snippet)).not.toThrow();
        });
    }
});
