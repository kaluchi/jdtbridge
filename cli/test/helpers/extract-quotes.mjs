// Shared `~{…}` Quote-literal extractor for `*-examples.test.mjs`
// gates that verify catalog snippets parse.
//
// Brace nesting is balanced through a depth counter so Map / Vec
// literals (`~{ {:k 1} | … }`) and nested Quotes (`~{ ~{inner} }`)
// round-trip to the parser intact. String literals skip the brace
// scan so a `"}"` byte inside a snippet does not close the
// surrounding Quote prematurely.

export function extractQuotes(source) {
    const out = [];
    let i = 0;
    while (i < source.length - 1) {
        if (source[i] === '~' && source[i + 1] === '{') {
            const start = i + 2;
            let j = start;
            let depth = 1;
            while (j < source.length && depth > 0) {
                const c = source[j];
                if (c === '\\' && j + 1 < source.length) { j += 2; continue; }
                if (c === '"') {
                    j++;
                    while (j < source.length && source[j] !== '"') {
                        if (source[j] === '\\') j += 2;
                        else j++;
                    }
                    j++;
                    continue;
                }
                if (c === '{') depth++;
                else if (c === '}') depth--;
                j++;
            }
            out.push(source.slice(start, j - 1));
            i = j;
        } else {
            i++;
        }
    }
    return out;
}
