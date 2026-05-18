// CI gate for the :jdt/coverage module's documented examples.
// Mirror of graph-examples.test.mjs — every `~{…}` Quote inside
// coverage.qlang must parse cleanly as a qlang pipeline. Pure
// syntactic check; semantic rot (stale fqn, dead coverageId) needs
// a live session and is out of scope.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import { describe, it, expect } from 'vitest';
import { parse } from '@kaluchi/qlang-core';

const __dirname = dirname(fileURLToPath(import.meta.url));
const COVERAGE_QLANG = resolve(
        __dirname, '..', 'lib', 'jdt', 'coverage.qlang');

const QUOTE_RE = /~\{((?:\\.|[^}\\])*)\}/g;

function extractSnippets(source) {
    const out = [];
    for (const match of source.matchAll(QUOTE_RE)) {
        out.push(match[1]);
    }
    return out;
}

describe(':jdt/coverage examples parse as qlang', () => {
    const source = readFileSync(COVERAGE_QLANG, 'utf8');
    const snippets = extractSnippets(source);

    it('catalog lists every declared :examples snippet', () => {
        expect(snippets.length).toBeGreaterThan(0);
        const occurrences =
                (source.match(/~\{/g) ?? []).length;
        expect(snippets.length).toBe(occurrences);
    });

    for (const snippet of snippets) {
        const label = snippet.length > 60
                ? snippet.slice(0, 57) + '…' : snippet;
        it(`parses: ${label}`, () => {
            expect(() => parse(snippet)).not.toThrow();
        });
    }
});
