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
import { extractQuotes } from './helpers/extract-quotes.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const COVERAGE_QLANG = resolve(
        __dirname, '..', 'lib', 'jdt', 'coverage.qlang');

describe(':jdt/coverage examples parse as qlang', () => {
    const source = readFileSync(COVERAGE_QLANG, 'utf8');
    const snippets = extractQuotes(source);

    it('catalog lists at least one declared example snippet', () => {
        expect(snippets.length).toBeGreaterThan(0);
    });

    for (const snippet of snippets) {
        const label = snippet.length > 60
                ? snippet.slice(0, 57) + '…' : snippet;
        it(`parses: ${label}`, () => {
            expect(() => parse(snippet)).not.toThrow();
        });
    }
});
