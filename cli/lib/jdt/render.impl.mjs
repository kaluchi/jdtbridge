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
import { keyword } from '@kaluchi/qlang-core';

// ── Map accessor helpers ────────────────────────────────────────
//
// qlang node-Maps are JS Map objects keyed by interned Keyword
// values. Every access goes through a keyword() lookup; interning
// once at module load keeps the hot path allocation-free.

// Bundle-level keys
const K_NODE       = keyword('node');
const K_TEXT       = keyword('text');
const K_OUTGOING   = keyword('outgoing');
const K_INCOMING   = keyword('incoming');
const K_SUPERS     = keyword('supers');
const K_SUBTYPES   = keyword('subtypes');
const K_MEMBERS    = keyword('members');
const K_REFS       = keyword('refs');

// Node-Map fields
const K_FQN           = keyword('fqn');
const K_KIND          = keyword('kind');
const K_TYPE_KIND     = keyword('typeKind');
const K_NAME          = keyword('name');
const K_SIGNATURE     = keyword('signature');
const K_TYPE          = keyword('type');
const K_MODIFIERS     = keyword('modifiers');
const K_RETURN_TYPE   = keyword('returnType');
const K_CONTAINING_TP = keyword('containingType');
const K_LOCATION      = keyword('location');
const K_FILE          = keyword('file');
const K_START_LINE    = keyword('startLine');
const K_END_LINE      = keyword('endLine');
const K_JAVADOC       = keyword('javadocSummary');

// Reference-record fields
const K_FROM          = keyword('from');
const K_TO            = keyword('to');
const K_REF_KIND      = keyword('refKind');

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
 *   :incoming  — Vec of :reference records produced by @refs
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
        out.push('#### ' + outgoingHeader(subjectKind) + ':');
        out.push(...renderRefGroup(outgoing, 'to'));
    }

    const incoming = mapGet(bundle, K_INCOMING);
    if (Array.isArray(incoming) && incoming.length > 0) {
        out.push('');
        out.push('#### ' + incomingHeader(subjectKind) + ':');
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

/**
 * Header phrasing for the Outgoing / Incoming ref sections. A
 * method's refs are typically calls; a field's are accesses; a
 * type's are references (typeUse / extends / parameter). Matches
 * the vocabulary a reader already uses when talking about the
 * subject kind.
 */
function outgoingHeader(subjectKind) {
    switch (subjectKind) {
        case 'method': return 'Outgoing calls';
        case 'field':  return 'Outgoing accesses';
        case 'type':   return 'Outgoing references';
        default:       return 'Outgoing';
    }
}

function incomingHeader(subjectKind) {
    switch (subjectKind) {
        case 'method': return 'Incoming calls';
        case 'field':  return 'Incoming accesses';
        case 'type':   return 'Incoming references';
        default:       return 'Incoming';
    }
}

// ── Reference-group rendering ──────────────────────────────────

/**
 * Render Vec of :reference records into a flat list. Each record
 * carries :from (the calling site), :to (the target), :refKind.
 * `sideKey` picks which side is the "other" — for outgoing refs
 * from the viewed member we show :to, for incoming :from.
 *
 * A line per distinct target FQMN; the `[badge] fqmn` form is the
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
        const returnType = mapGet(other, K_RETURN_TYPE);
        const javadoc = mapGet(other, K_JAVADOC);
        let line = badgeOf(other) + ' `' + fqn + '`';
        if (typeof returnType === 'string') {
            line += ' → `' + returnType + '`';
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
 * Input: Vec of :reference records (result of @refs /
 * @outgoingRefs). Output: flat markdown list one line per ref,
 * `[badge] fqmn → returnType — javadoc`, grouped by refKind when
 * the Vec spans multiple kinds.
 */
function formatMdRefs(refs) {
    if (!Array.isArray(refs)) {
        throw new TypeError(
            'mdRefs expects a Vec of :reference records, got '
            + describe(refs));
    }
    if (refs.length === 0) return '';

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
        // Both :from and :to may be interesting; show the "other"
        // end relative to refKind semantics — incoming-style refs
        // carry :from, outgoing-style :to. When both are present
        // (canonical reference shape) prefer :to for call/read/
        // write/typeUse (target), fall back to :from.
        out.push(...renderRefGroup(byKind.get(kind), 'to'));
    }
    return out.join('\n');
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
}
