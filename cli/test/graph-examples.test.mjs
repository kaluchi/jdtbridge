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
// `jdt q ':@name | runExamples'`. What this gate does cover
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

// Match `~{…}` Quote literals — qlang 0.7 carries examples as
// embedded Quote bodies inside attached doc-prefix blocks. Balanced
// braces are not supported by regex, so we accept the first `}`
// that is not preceded by an escape character. Snippets that contain
// a literal `}` would need a richer extractor; the catalog stays
// brace-free in example bodies by convention.
const QUOTE_RE = /~\{((?:\\.|[^}\\])*)\}/g;

function extractSnippets(source) {
    const snippets = [];
    for (const match of source.matchAll(QUOTE_RE)) {
        snippets.push(match[1]);
    }
    return snippets;
}

describe(':jdt/graph examples parse as qlang', () => {
    const graphSource = readFileSync(GRAPH_QLANG, 'utf8');
    const snippets = extractSnippets(graphSource);

    it('catalog lists every declared :examples snippet', () => {
        expect(snippets.length).toBeGreaterThan(0);
        // Sanity: the count matches the raw `~{` occurrences.
        const snippetOccurrences =
            (graphSource.match(/~\{/g) ?? []).length;
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
