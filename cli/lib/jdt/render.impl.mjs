// Host-bound render-operand impls for `:jdt/render`.
//
// Pure value transforms — every operand takes a node-Map bundle
// from pipeValue and returns a markdown String. No HTTP, no
// Eclipse: the bundle must already hold everything the renderer
// needs, collected by upstream `:jdt/graph` / `:jdt/coverage`
// conduits or assembled by hand.
//
// qlang 0.7 invariants applied here:
//   * Map keys are plain Strings — Keyword objects ride as VALUES.
//   * Bundle / node shape mismatches lift through per-site error
//     descriptors carrying `:kind ::BundleNodeShapeError` /
//     `:kind ::BundleCoverageShapeError` (declared in
//     `lib/jdt/render.qlang`), surfacing through `result !| type`.
//   * Catalog body in `render.qlang` declares the operand surface;
//     `createImpls()` below pairs each declaration with its JS
//     dispatch wrapper, and `use(:jdt/render)` stamps `:impl`
//     onto every descriptor through `stampStructuralFacts` —
//     same path `:jdt/graph` / `:jdt/coverage` follow.

import { valueOp } from '@kaluchi/qlang-core/dispatch';
import {
    keyword,
    makeErrorValue,
    makeTagKeyword,
} from '@kaluchi/qlang-core';

// ── Map accessor helpers ────────────────────────────────────────
//
// qlang 0.7 node-Maps are JS Map objects keyed by plain Strings —
// Keyword objects ride only as VALUES (pipeline-visible identifiers
// carrying `.literal`). The string-key constants below are named
// symbolically so a rename ripples through one place.

// Bundle-level keys
const K_NODE       = 'node';
const K_TEXT       = 'text';
const K_OUTGOING   = 'outgoing';
const K_INCOMING   = 'incoming';
const K_SUPERS     = 'supers';
const K_SUBTYPES   = 'subtypes';
const K_MEMBERS    = 'members';
const K_COVERAGE   = 'coverage';

// Coverage-bundle and node-Map keys
const K_COUNTERS         = 'counters';
const K_LINES_BUNDLE     = 'lines';
const K_ENTRIES          = 'entries';
const K_STATUS           = 'status';
const K_LINE_NUMBER      = 'line';
const K_COVERED_COUNT    = 'coveredCount';
const K_MISSED_COUNT     = 'missedCount';
const K_TOTAL_COUNT      = 'totalCount';
const K_COVERED_RATIO    = 'coveredRatio';
const K_BRANCH_COVERED   = 'branchCovered';
const K_BRANCH_MISSED    = 'branchMissed';
const K_C_INSTRUCTION    = 'instruction';
const K_C_BRANCH         = 'branch';
const K_C_LINE           = 'line';
const K_C_METHOD         = 'method';
const K_C_CLASS          = 'class';
const K_C_COMPLEXITY     = 'complexity';

// Node-Map fields
const K_FQN           = 'fqn';
const K_KIND          = 'kind';
const K_TYPE_KIND     = 'typeKind';
const K_NAME          = 'name';
const K_SIGNATURE     = 'signature';
const K_TYPE          = 'type';
const K_MODIFIERS     = 'modifiers';
const K_RETURN_TYPE   = 'returnType';
const K_LOCATION      = 'location';
const K_FILE          = 'file';
const K_START_LINE    = 'startLine';
const K_END_LINE      = 'endLine';
const K_JAVADOC       = 'javadocSummary';

// Reference-record fields
const K_FROM          = 'from';
const K_TO            = 'to';
const K_REF_KIND      = 'refKind';
const K_DIRECTION     = 'direction';

function mapGet(m, key) {
    return m instanceof Map ? m.get(key) : undefined;
}

function describeShape(v) {
    if (v === null) return 'null';
    if (Array.isArray(v)) return 'vec';
    if (v instanceof Map) return 'map';
    return typeof v;
}

function bundleNodeShapeError(operandName, actualShape) {
    const ctx = new Map();
    ctx.set('operand', keyword(operandName));
    ctx.set('actualShape', actualShape);
    const descriptor = new Map();
    descriptor.set('kind', makeTagKeyword('BundleNodeShapeError'));
    descriptor.set('origin', keyword('jdt/render'));
    descriptor.set('message',
            `${operandName}: bundle :node must be a node-Map with `
            + `:fqn and :kind, got ${actualShape}`);
    descriptor.set('context', ctx);
    return makeErrorValue(descriptor);
}

function bundleCoverageShapeError(actualShape) {
    const ctx = new Map();
    ctx.set('operand', keyword('mdCoverage'));
    ctx.set('actualShape', actualShape);
    const descriptor = new Map();
    descriptor.set('kind', makeTagKeyword('BundleCoverageShapeError'));
    descriptor.set('origin', keyword('jdt/render'));
    descriptor.set('message',
            'mdCoverage: bundle :coverage must be a /coverage/node '
            + `Map from @coverage, got ${actualShape}`);
    descriptor.set('context', ctx);
    return makeErrorValue(descriptor);
}

// ── Badge selection ────────────────────────────────────────────

const KIND_BADGE = {
    method: '[M]',
    field: '[F]',
    type: '[C]',
    package: '[P]',
    project: '[J]',
    file: '[FI]',
    reference: '[R]',
    problem: '[E]',
};

const TYPE_KIND_BADGE = {
    class: '[C]',
    interface: '[I]',
    enum: '[E]',
    annotation: '[A]',
    record: '[R]',
};

// Pick the one-shot `[X]` badge for a node-Map. Types route through
// :typeKind so an interface reads as `[I]` rather than the default
// `[C]` that `:kind "type"` alone would yield. Constants (static +
// final fields) get `[K]` instead of `[F]`.
function badgeOf(node) {
    if (!(node instanceof Map)) return '[?]';
    const kind = mapGet(node, K_KIND);
    if (kind === 'type') {
        const tk = mapGet(node, K_TYPE_KIND);
        return TYPE_KIND_BADGE[tk] ?? '[C]';
    }
    if (kind === 'field') {
        const mods = mapGet(node, K_MODIFIERS);
        if (Array.isArray(mods)
                && mods.includes('static')
                && mods.includes('final')) {
            return '[K]';
        }
    }
    return KIND_BADGE[kind] ?? '[?]';
}

// ── Location rendering ─────────────────────────────────────────

function locationLine(node) {
    const loc = mapGet(node, K_LOCATION);
    if (!(loc instanceof Map)) return null;
    const file = mapGet(loc, K_FILE);
    const startLine = mapGet(loc, K_START_LINE);
    const endLine = mapGet(loc, K_END_LINE);
    if (typeof file !== 'string') return null;
    let range = '';
    if (typeof startLine === 'number') {
        range = ':' + startLine;
        if (typeof endLine === 'number' && endLine !== startLine) {
            range += '-' + endLine;
        }
    }
    return '`' + file + range + '`';
}

// ── mdSource: method / field / type card ───────────────────────

function formatMdSource(bundle) {
    if (!(bundle instanceof Map)) {
        return bundleNodeShapeError('mdSource', describeShape(bundle));
    }
    const node = mapGet(bundle, K_NODE);
    if (!(node instanceof Map)) {
        return bundleNodeShapeError('mdSource', describeShape(node));
    }

    const out = [];
    const fqn = mapGet(node, K_FQN) ?? '?';
    const loc = locationLine(node);
    out.push('#### ' + badgeOf(node) + ' ' + fqn);
    if (loc) out.push(loc);
    out.push('');

    const text = mapGet(bundle, K_TEXT);
    if (typeof text === 'string' && text.length > 0) {
        out.push('```java');
        out.push(text.replace(/\s+$/, ''));
        out.push('```');
    }

    const subjectKind = mapGet(node, K_KIND);
    const outgoing = mapGet(bundle, K_OUTGOING);
    if (Array.isArray(outgoing) && outgoing.length > 0) {
        out.push('');
        out.push('#### ' + refSectionHeader('Outgoing', subjectKind) + ':');
        out.push(...renderRefGroup(outgoing, 'to'));
    }

    const incoming = mapGet(bundle, K_INCOMING);
    if (Array.isArray(incoming) && incoming.length > 0) {
        out.push('');
        out.push('#### ' + refSectionHeader('Incoming', subjectKind) + ':');
        out.push(...renderRefGroup(incoming, 'from'));
    }

    const supers = mapGet(bundle, K_SUPERS);
    const subs = mapGet(bundle, K_SUBTYPES);
    if ((Array.isArray(supers) && supers.length > 0)
            || (Array.isArray(subs) && subs.length > 0)) {
        out.push('');
        out.push('#### Hierarchy:');
        for (const s of supers ?? []) {
            out.push('↑ ' + badgeOf(s) + ' `'
                    + (mapGet(s, K_FQN) ?? '?') + '`');
        }
        for (const s of subs ?? []) {
            out.push('↓ ' + badgeOf(s) + ' `'
                    + (mapGet(s, K_FQN) ?? '?') + '`');
        }
    }

    return out.join('\n');
}

const REF_NOUN = {
    method: 'calls',
    field:  'accesses',
    type:   'references',
};

function refSectionHeader(direction, subjectKind) {
    return direction + ' ' + (REF_NOUN[subjectKind] ?? 'refs');
}

// ── Reference-group rendering ──────────────────────────────────

// Render Vec of :reference records into a flat list. Each record
// carries :from (the calling site), :to (the target), :refKind.
// `sideKey` picks which side is the "other" — for outgoing refs
// from the viewed member we show :to, for incoming :from.
function renderRefGroup(refs, sideKey) {
    const key = sideKey === 'to' ? K_TO : K_FROM;
    const lines = [];
    const seen = new Set();
    for (const ref of refs) {
        if (!(ref instanceof Map)) continue;
        const other = mapGet(ref, key);
        if (!(other instanceof Map)) continue;
        const fqn = mapGet(other, K_FQN);
        if (typeof fqn !== 'string' || seen.has(fqn)) continue;
        seen.add(fqn);
        // Method skeletons carry :returnType; field skeletons carry
        // :type (erased). Render `→ ReturnType` for methods, `: Type`
        // for fields. Types and other kinds leave the suffix empty.
        const returnType = mapGet(other, K_RETURN_TYPE);
        const fieldType  = mapGet(other, K_TYPE);
        const javadoc = mapGet(other, K_JAVADOC);
        let line = badgeOf(other) + ' `' + fqn + '`';
        if (typeof returnType === 'string') {
            line += ' → `' + returnType + '`';
        } else if (typeof fieldType === 'string') {
            line += ' : `' + fieldType + '`';
        }
        if (typeof javadoc === 'string' && javadoc.length > 0) {
            line += ' — ' + javadoc;
        }
        lines.push(line);
    }
    return lines;
}

// ── mdHierarchy: ↑/↓ tree for a type ───────────────────────────

function formatMdHierarchy(bundle) {
    if (!(bundle instanceof Map)) {
        return bundleNodeShapeError('mdHierarchy', describeShape(bundle));
    }
    const node = mapGet(bundle, K_NODE);
    if (!(node instanceof Map)) {
        return bundleNodeShapeError('mdHierarchy', describeShape(node));
    }
    const out = [];
    out.push('#### ' + badgeOf(node) + ' '
            + (mapGet(node, K_FQN) ?? '?'));
    const loc = locationLine(node);
    if (loc) out.push(loc);
    out.push('');

    const supers = mapGet(bundle, K_SUPERS);
    if (Array.isArray(supers) && supers.length > 0) {
        out.push('#### Supertypes:');
        for (const s of supers) {
            out.push('↑ ' + badgeOf(s) + ' `'
                    + (mapGet(s, K_FQN) ?? '?') + '`');
        }
    }

    const subs = mapGet(bundle, K_SUBTYPES);
    if (Array.isArray(subs) && subs.length > 0) {
        if (out[out.length - 1] !== '') out.push('');
        out.push('#### Subtypes:');
        for (const s of subs) {
            out.push('↓ ' + badgeOf(s) + ' `'
                    + (mapGet(s, K_FQN) ?? '?') + '`');
        }
    }

    return out.join('\n');
}

// ── mdRefs: flat refs list (Vec of reference records) ─────────

// Render a Vec of :reference records as a flat markdown list, one
// line per distinct target, grouped by :refKind when the Vec
// mixes kinds. Subject side is picked from :direction on the
// first record carrying it; absent :direction → :to.
function formatMdRefs(refs) {
    if (!Array.isArray(refs)) return '';
    if (refs.length === 0) return '';

    const sideKey = pickRefSide(refs);

    const byKind = new Map();
    for (const ref of refs) {
        if (!(ref instanceof Map)) continue;
        const kind = mapGet(ref, K_REF_KIND) ?? 'ref';
        let bucket = byKind.get(kind);
        if (!bucket) {
            bucket = [];
            byKind.set(kind, bucket);
        }
        bucket.push(ref);
    }

    const out = [];
    const kinds = [...byKind.keys()];
    const multiKind = kinds.length > 1;
    for (const kind of kinds) {
        if (multiKind) {
            if (out.length > 0) out.push('');
            out.push('#### ' + kindHeader(kind) + ':');
        }
        out.push(...renderRefGroup(byKind.get(kind), sideKey));
    }
    return out.join('\n');
}

// First record with a recognised :direction wins; all-absent → 'to'.
function pickRefSide(refs) {
    for (const ref of refs) {
        if (!(ref instanceof Map)) continue;
        const dir = mapGet(ref, K_DIRECTION);
        if (dir === 'incoming') return 'from';
        if (dir === 'outgoing') return 'to';
    }
    return 'to';
}

function kindHeader(kind) {
    switch (kind) {
        case 'call':    return 'Calls';
        case 'read':    return 'Reads';
        case 'write':   return 'Writes';
        case 'typeUse': return 'Type uses';
        default:        return kind;
    }
}

// ── mdOutline: structural tree of a type ───────────────────────

function formatMdOutline(bundle) {
    if (!(bundle instanceof Map)) {
        return bundleNodeShapeError('mdOutline', describeShape(bundle));
    }
    const node = mapGet(bundle, K_NODE);
    if (!(node instanceof Map)) {
        return bundleNodeShapeError('mdOutline', describeShape(node));
    }

    const out = [];
    out.push('#### ' + badgeOf(node) + ' '
            + (mapGet(node, K_FQN) ?? '?'));
    const loc = locationLine(node);
    if (loc) out.push(loc);
    out.push('');

    const members = mapGet(bundle, K_MEMBERS);
    if (!Array.isArray(members) || members.length === 0) {
        return out.join('\n').replace(/\n+$/, '');
    }

    const fields = [];
    const methods = [];
    const innerTypes = [];
    for (const m of members) {
        if (!(m instanceof Map)) continue;
        const kind = mapGet(m, K_KIND);
        if (kind === 'field')       fields.push(m);
        else if (kind === 'method') methods.push(m);
        else if (kind === 'type')   innerTypes.push(m);
    }

    if (fields.length > 0) {
        out.push('#### Fields:');
        for (const f of fields) out.push(outlineFieldLine(f));
        out.push('');
    }
    if (methods.length > 0) {
        out.push('#### Methods:');
        for (const m of methods) out.push(outlineMethodLine(m));
        out.push('');
    }
    if (innerTypes.length > 0) {
        out.push('#### Inner types:');
        for (const t of innerTypes) out.push(outlineInnerTypeLine(t));
    }

    return out.join('\n').replace(/\n+$/, '');
}

function outlineFieldLine(field) {
    const name = mapGet(field, K_NAME)
            ?? fqnLocalName(mapGet(field, K_FQN));
    const type = mapGet(field, K_TYPE);
    let line = badgeOf(field) + ' ' + name;
    if (typeof type === 'string') line += ' : ' + type;
    const modSuffix = modifierSuffix(field);
    if (modSuffix) line += ' ' + modSuffix;
    const range = lineRangeSuffix(field);
    if (range) line += '  ' + range;
    return line;
}

function outlineMethodLine(method) {
    const sig = mapGet(method, K_SIGNATURE)
            ?? fqnLocalName(mapGet(method, K_FQN));
    const returnType = mapGet(method, K_RETURN_TYPE);
    let line = badgeOf(method) + ' ' + sig;
    if (typeof returnType === 'string') line += ' : ' + returnType;
    const modSuffix = modifierSuffix(method);
    if (modSuffix) line += ' ' + modSuffix;
    const range = lineRangeSuffix(method);
    if (range) line += '  ' + range;
    return line;
}

function outlineInnerTypeLine(type) {
    const name = fqnLocalName(mapGet(type, K_FQN));
    let line = badgeOf(type) + ' ' + (name ?? '?');
    const modSuffix = modifierSuffix(type);
    if (modSuffix) line += ' ' + modSuffix;
    const range = lineRangeSuffix(type);
    if (range) line += '  ' + range;
    return line;
}

function fqnLocalName(fqn) {
    if (typeof fqn !== 'string') return null;
    const hash = fqn.indexOf('#');
    if (hash >= 0) return fqn.slice(hash + 1);
    const dot = fqn.lastIndexOf('.');
    return dot >= 0 ? fqn.slice(dot + 1) : fqn;
}

function modifierSuffix(node) {
    const mods = mapGet(node, K_MODIFIERS);
    if (!Array.isArray(mods) || mods.length === 0) return '';
    return '(' + mods.join(', ') + ')';
}

function lineRangeSuffix(node) {
    const loc = mapGet(node, K_LOCATION);
    if (!(loc instanceof Map)) return '';
    const startLine = mapGet(loc, K_START_LINE);
    const endLine = mapGet(loc, K_END_LINE);
    if (typeof startLine !== 'number') return '';
    if (typeof endLine !== 'number' || endLine === startLine) {
        return String(startLine);
    }
    return startLine + '-' + endLine;
}

// ── mdCoverage: counters table + per-line ranges ───────────────

const COUNTER_ROWS = [
    [K_C_INSTRUCTION, 'Instructions'],
    [K_C_BRANCH,      'Branches'],
    [K_C_LINE,        'Lines'],
    [K_C_METHOD,      'Methods'],
    [K_C_CLASS,       'Types'],
    [K_C_COMPLEXITY,  'Complexity'],
];

function formatMdCoverage(bundle) {
    if (!(bundle instanceof Map)) {
        return bundleNodeShapeError('mdCoverage', describeShape(bundle));
    }
    const node = mapGet(bundle, K_NODE);
    if (!(node instanceof Map)) {
        return bundleNodeShapeError('mdCoverage', describeShape(node));
    }
    const coverage = mapGet(bundle, K_COVERAGE);
    if (!(coverage instanceof Map)) {
        return bundleCoverageShapeError(describeShape(coverage));
    }

    const out = [];
    out.push('#### ' + badgeOf(node) + ' '
            + (mapGet(node, K_FQN) ?? '?'));
    const loc = locationLine(node);
    if (loc) out.push(loc);
    out.push('');

    const counters = mapGet(coverage, K_COUNTERS);
    if (counters instanceof Map) {
        out.push('#### Coverage:');
        out.push('');
        out.push(...formatCountersTable(counters));
    }

    const linesBundle = mapGet(coverage, K_LINES_BUNDLE);
    if (linesBundle instanceof Map) {
        const entries = mapGet(linesBundle, K_ENTRIES);
        if (Array.isArray(entries) && entries.length > 0) {
            out.push('');
            out.push('#### Lines:');
            out.push(...formatLinesBlock(entries));
        }
    }

    return out.join('\n');
}

function formatCountersTable(counters) {
    const lines = [];
    lines.push(
        '| Counter      | Coverage | Covered | Missed |    Total |');
    lines.push(
        '|--------------|---------:|--------:|-------:|---------:|');
    for (const [wireKey, label] of COUNTER_ROWS) {
        const c = mapGet(counters, wireKey);
        if (!(c instanceof Map)) continue;
        const cov   = mapGet(c, K_COVERED_COUNT) ?? 0;
        const miss  = mapGet(c, K_MISSED_COUNT)  ?? 0;
        const total = mapGet(c, K_TOTAL_COUNT)   ?? 0;
        const ratio = mapGet(c, K_COVERED_RATIO);
        lines.push('| ' + label.padEnd(12)
                + ' | ' + formatRatio(ratio).padStart(8)
                + ' | ' + String(cov).padStart(7)
                + ' | ' + String(miss).padStart(6)
                + ' | ' + String(total).padStart(8)
                + ' |');
    }
    return lines;
}

// EclEmma's `0.0 %` format — one decimal, space before `%`. Empty
// string when total = 0 (server emits null ratio in that case).
function formatRatio(r) {
    if (r === null || r === undefined || Number.isNaN(r)) return '';
    return (r * 100).toFixed(1) + ' %';
}

function formatLinesBlock(entries) {
    const covered = [];
    const partial = [];
    const uncovered = [];
    for (const e of entries) {
        if (!(e instanceof Map)) continue;
        const status = mapGet(e, K_STATUS);
        const line = mapGet(e, K_LINE_NUMBER);
        if (typeof line !== 'number') continue;
        if (status === 'FULLY_COVERED')      covered.push(line);
        else if (status === 'PARTLY_COVERED') partial.push(e);
        else if (status === 'NOT_COVERED')    uncovered.push(line);
    }
    const out = [];
    if (covered.length > 0) {
        out.push('  Covered:   ' + collapseRanges(covered));
    }
    if (partial.length > 0) {
        out.push('  Partial:   '
                + partial.map(formatPartialEntry).join(', '));
    }
    if (uncovered.length > 0) {
        out.push('  Uncovered: ' + collapseRanges(uncovered));
    }
    return out;
}

// Per-line partial entry: line number plus the branch hover-text
// EclEmma uses on its gutter annotations — `{0} of {1} branches
// missed.` (verbatim from `AnnotationTextSomeBranchesMissed_message`
// in EclEmma's `uimessages.properties`).
function formatPartialEntry(entry) {
    const line   = mapGet(entry, K_LINE_NUMBER);
    const missed = mapGet(entry, K_BRANCH_MISSED);
    const cov    = mapGet(entry, K_BRANCH_COVERED);
    const total  = (typeof missed === 'number' ? missed : 0)
                 + (typeof cov    === 'number' ? cov    : 0);
    if (typeof missed === 'number' && total > 0) {
        return line + ' (' + missed + ' of ' + total
                + ' branches missed)';
    }
    return String(line);
}

// Collapse a sorted (or unsorted) list of integers into
// comma-joined consecutive ranges: `[42,43,44,46,50,51,52]`
// → `"42-44, 46, 50-52"`.
function collapseRanges(numbers) {
    if (numbers.length === 0) return '';
    const sorted = [...numbers].sort((a, b) => a - b);
    const out = [];
    let start = sorted[0];
    let end = sorted[0];
    for (let i = 1; i < sorted.length; i++) {
        const n = sorted[i];
        if (n === end + 1) {
            end = n;
        } else {
            out.push(start === end ? String(start)
                    : start + '-' + end);
            start = n;
            end = n;
        }
    }
    out.push(start === end ? String(start) : start + '-' + end);
    return out.join(', ');
}

// ── Operand impls — keyed by catalog binding name ─────────────

export function createImpls() {
    return {
        mdSource:    valueOp('mdSource',    1, async (b) => formatMdSource(b)),
        mdHierarchy: valueOp('mdHierarchy', 1, async (b) => formatMdHierarchy(b)),
        mdOutline:   valueOp('mdOutline',   1, async (b) => formatMdOutline(b)),
        mdRefs:      valueOp('mdRefs',      1, async (b) => formatMdRefs(b)),
        mdCoverage:  valueOp('mdCoverage',  1, async (b) => formatMdCoverage(b)),
    };
}
