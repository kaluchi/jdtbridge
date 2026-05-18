// Host-bound render operands for the :jdt/graph module.
//
// Each operand is a pure value transform: takes a node-Map bundle
// from pipeValue, returns a markdown String. No HTTP, no session
// state — the bundle must already hold everything the renderer
// needs, collected by upstream graph axes.
//
// The contract is documented in jdt-query-spec.md § Markdown
// rendering; the badge legend and the "server exhaustive, client
// formats" split live there.

import { valueOp } from '@kaluchi/qlang-core/dispatch';

// ── Map accessor helpers ────────────────────────────────────────
//
// qlang 0.7 node-Maps are JS Map objects keyed by plain Strings
// — Keyword objects ride only as VALUES (pipeline-visible
// identifiers carrying `.literal`). The string-key constants below
// are named symbolically so a rename ripples through one place.

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
const K_CONTAINING_TP = 'containingType';
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

function ensureMap(value, operandName) {
    if (!(value instanceof Map)) {
        throw new TypeError(
            `${operandName} expects a node-Map bundle, got ${
                describe(value)}`);
    }
    return value;
}

function describe(v) {
    if (v === null) return 'null';
    if (Array.isArray(v)) return 'Vec';
    if (v instanceof Map) return 'Map';
    return typeof v;
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

/**
 * Pick the one-shot `[X]` badge for a node-Map. Types route through
 * :typeKind so an interface reads as `[I]` rather than the default
 * `[C]` that `:kind "type"` alone would yield. Constants (static +
 * final fields) get `[K]` instead of `[F]`.
 */
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

/**
 * Bundle shape for mdSource (all keys optional but :node required):
 *   :node      — detail node-Map of the viewed member
 *   :text      — source text String
 *   :outgoing  — Vec of :reference records produced by @outgoingRefs
 *   :incoming  — Vec of :reference records produced by @incomingRefs
 *   :supers    — Vec of :type skeletons (type-level only)
 *   :subtypes  — Vec of :type skeletons (type-level only)
 *
 * Any missing section is skipped — determinism rule: a section
 * appears iff its data was supplied. Never collapsed, never
 * summarised, never flag-gated.
 */
function formatMdSource(bundle) {
    ensureMap(bundle, 'mdSource');
    const node = mapGet(bundle, K_NODE);
    if (!(node instanceof Map)) {
        throw new TypeError(
            'mdSource: :node must be a node-Map carrying :fqn and :kind');
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

/**
 * Render Vec of :reference records into a flat list. Each record
 * carries :from (the calling site), :to (the target), :refKind.
 * `sideKey` picks which side is the "other" — for outgoing refs
 * from the viewed member we show :to, for incoming :from.
 *
 * A line per distinct target FQN; the `[badge] fqn` form is the
 * zero-modification-navigation primitive — copy a line and
 * `jdt q '"…" | @source'` renders its card.
 */
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

/**
 * Bundle shape:
 *   :node     — :type detail node-Map (required)
 *   :supers   — Vec of :type skeletons (direct parents)
 *   :subtypes — Vec of :type skeletons (direct children)
 *
 * Renders the two sections with arrows; no depth indent at the
 * MVP — flatten the list. Transitive hierarchy is the caller's
 * choice (feed @ancestors / @descendants into :supers / :subtypes
 * for the full chain).
 */
function formatMdHierarchy(bundle) {
    ensureMap(bundle, 'mdHierarchy');
    const node = mapGet(bundle, K_NODE);
    if (!(node instanceof Map)) {
        throw new TypeError(
            'mdHierarchy: :node must be a node-Map');
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

/**
 * Render a Vec of :reference records as a flat markdown list, one
 * line per distinct target, grouped by :refKind when the Vec
 * mixes kinds. Subject side is picked from :direction on the
 * first record carrying it; absent :direction → :to.
 */
function formatMdRefs(refs) {
    if (!Array.isArray(refs)) {
        throw new TypeError(
            'mdRefs expects a Vec of :reference records, got '
            + describe(refs));
    }
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

/**
 * Bundle shape for mdOutline:
 *   :node    — :type detail node-Map (required)
 *   :members — Vec of member skeletons (methods / fields /
 *              innerTypes). Upstream fetches via @members.
 *
 * Renders the viewed type header, then three sub-sections in
 * source convention order — fields, methods, inner types — each
 * line a one-row signature with badge, modifiers, and line
 * range. Inner types drop in as `[C] Name` without recursion;
 * feed them separately for a nested outline.
 */
function formatMdOutline(bundle) {
    ensureMap(bundle, 'mdOutline');
    const node = mapGet(bundle, K_NODE);
    if (!(node instanceof Map)) {
        throw new TypeError(
            'mdOutline: :node must be a :type detail node-Map');
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

/**
 * Bundle shape for mdCoverage:
 *   :node     — detail node-Map of the viewed element (required)
 *   :coverage — /coverage/node response Map: :counters always,
 *               :lines block when subject is an ISourceNode
 *
 * Layout follows EclEmma's Coverage View vocabulary verbatim:
 *
 *   `Element` (header) `Counter | Coverage | Covered | Missed | Total`
 *
 * with EclEmma counter names (`Types` not `Classes`, `Complexity`
 * not `Cxty`) and `0.0 %` ratio format. The Lines section, when
 * present, splits entries into Covered / Partial / Uncovered with
 * consecutive line numbers collapsed to ranges (`33-35, 39, 41-50`)
 * — same idiom as coverage.py's Missing column.
 */
function formatMdCoverage(bundle) {
    ensureMap(bundle, 'mdCoverage');
    const node = mapGet(bundle, K_NODE);
    if (!(node instanceof Map)) {
        throw new TypeError(
            'mdCoverage: :node must be a node-Map');
    }
    const coverage = mapGet(bundle, K_COVERAGE);
    if (!(coverage instanceof Map)) {
        throw new TypeError(
            'mdCoverage: :coverage must be a Map from @coverage, got '
            + describe(coverage));
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

/** EclEmma's `0.0 %` format — one decimal, space before `%`. Empty
 *  string when total = 0 (server emits null ratio in that case). */
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

/**
 * Per-line partial entry: line number plus the branch hover-text
 * EclEmma uses on its gutter annotations:
 *
 *   `{0} of {1} branches missed.`
 *
 * (verbatim from `AnnotationTextSomeBranchesMissed_message` in
 * EclEmma's `uimessages.properties`).
 */
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

/**
 * Collapse a sorted (or unsorted) list of integers into
 * comma-joined consecutive ranges: `[42,43,44,46,50,51,52]`
 * → `"42-44, 46, 50-52"`.
 */
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

// ── Operand bindings ───────────────────────────────────────────

export function bindJdtRenderOperands(session) {
    session.bind('mdSource', valueOp('mdSource', 1,
            async (bundle) => formatMdSource(bundle)));
    session.bind('mdHierarchy', valueOp('mdHierarchy', 1,
            async (bundle) => formatMdHierarchy(bundle)));
    session.bind('mdOutline', valueOp('mdOutline', 1,
            async (bundle) => formatMdOutline(bundle)));
    session.bind('mdRefs', valueOp('mdRefs', 1,
            async (refs) => formatMdRefs(refs)));
    session.bind('mdCoverage', valueOp('mdCoverage', 1,
            async (bundle) => formatMdCoverage(bundle)));
}
