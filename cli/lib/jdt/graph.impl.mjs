// JS-side implementations for the :jdt/graph qlang module.
//
// Discipline: every @-operand is NULLARY. Subject is taken from
// pipeValue only. Strings flow into the pipeline as seed values
// (`"fqn" | @op`); navigation chains read the :fqn from a Map
// node when subject is a node from a previous step. Captured
// arguments to @-operands are forbidden — they hide what data
// flows into the operand and reduce composability.
//
// Filtering, distribution, projection, fan-out — all done via
// core qlang combinators (filter / * / >> / | / !| / as / let).
// No filter parameters in URLs, no refKind/scope/sourceOnly
// modifiers. The server emits each axis exhaustively; qlang
// composes the rest.
//
// Wire shape: server returns canonical node JSON or
// {"_error": {…}}. liftServerResponse converts to qlang shape
// and lifts errors via makeErrorValue (rides the fail-track).

import { nullaryOp, overloadedOp } from '@kaluchi/qlang-core/dispatch';
import { keyword, isKeyword, makeErrorValue } from '@kaluchi/qlang-core';
import { get } from '../../src/client.mjs';

// ── Conversion helpers ──────────────────────────────────────────

const FQN_KEY = keyword('fqn');

function jsonToQlang(jsonVal) {
    if (Array.isArray(jsonVal)) return jsonVal.map(jsonToQlang);
    if (jsonVal !== null && typeof jsonVal === 'object') {
        const m = new Map();
        for (const [k, v] of Object.entries(jsonVal)) {
            m.set(keyword(k), jsonToQlang(v));
        }
        return m;
    }
    return jsonVal;
}

/**
 * Pull the canonical identifier out of pipeValue. Map → :fqn
 * projection. String → returned as-is (literal seed). Anything
 * else → null (caller raises a fail-track error).
 */
function fqnOf(subject) {
    if (typeof subject === 'string') return subject;
    if (subject instanceof Map) {
        const fqn = subject.get(FQN_KEY);
        if (typeof fqn === 'string') return fqn;
    }
    return null;
}

function liftServerResponse(jsonVal) {
    if (jsonVal !== null
            && typeof jsonVal === 'object'
            && !Array.isArray(jsonVal)
            && jsonVal._error !== undefined) {
        return makeErrorValue(jsonToQlang(jsonVal._error));
    }
    return jsonToQlang(jsonVal);
}

function missingSubject(operandName, subject) {
    const ctx = new Map();
    ctx.set(keyword('operand'), operandName);
    ctx.set(keyword('subjectType'),
            subject === null || subject === undefined ? 'null'
            : Array.isArray(subject) ? 'vec'
            : subject instanceof Map ? 'map-without-fqn'
            : typeof subject);
    const descriptor = new Map();
    descriptor.set(keyword('kind'), keyword('missing-subject-fqn'));
    descriptor.set(keyword('thrown'), keyword('MissingSubjectFqn'));
    descriptor.set(keyword('origin'), keyword('jdt/graph'));
    descriptor.set(keyword('message'),
            `${operandName}: pipeValue must be a node-Map with :fqn or a fqn-String`);
    descriptor.set(keyword('context'), ctx);
    return makeErrorValue(descriptor);
}

const enc = encodeURIComponent;

/**
 * Per-axis HTTP timeout. Graph operands are non-interactive —
 * an LLM composes a pipeline and waits for the answer; a 10-30 s
 * ceiling on expensive queries (@outgoingRefs on large compilation
 * units, @refs with many hits, transitive walks) truncates
 * legitimate work mid-flight. Default is 5 minutes; an agent or
 * CI run can raise or lower via JDT_GRAPH_TIMEOUT_MS.
 */
const GRAPH_TIMEOUT_MS = (() => {
    const override = Number.parseInt(
        process.env.JDT_GRAPH_TIMEOUT_MS ?? '', 10);
    return Number.isFinite(override) && override > 0
        ? override
        : 300_000;
})();

/**
 * Cap simultaneous in-flight HTTP calls into the plugin. qlang `*`
 * runs its body through Promise.all over the entire subject Vec,
 * so `@projects * @members * @methods` naturally fans out into
 * hundreds of concurrent requests. Unbounded, that queues on
 * Eclipse's workspace lock — a handful finish, the rest wait past
 * the CLI timeout, and the Error Log fills with broken-pipe
 * entries once the CLI bails.
 *
 * The limiter is a cooperative semaphore: each getEndpoint call
 * waits until a slot frees, then holds it for the duration of the
 * HTTP round-trip. Order is FIFO over the wait queue.
 *
 * Default concurrency: 8. JDT_GRAPH_CONCURRENCY tunes per-process.
 */
const GRAPH_CONCURRENCY = (() => {
    const override = Number.parseInt(
        process.env.JDT_GRAPH_CONCURRENCY ?? '', 10);
    return Number.isFinite(override) && override > 0
        ? override
        : 8;
})();

let inFlight = 0;
const waiters = [];

function acquireSlot() {
    if (inFlight < GRAPH_CONCURRENCY) {
        inFlight++;
        return Promise.resolve();
    }
    return new Promise((resolve) => waiters.push(resolve));
}

function releaseSlot() {
    const next = waiters.shift();
    if (next) next();
    else inFlight--;
}

/**
 * Per-process request cache. Keyed by the full endpoint path
 * (including `?of=…` and `&refKind=…`). A qlang pipeline is a
 * short-lived process — typing the same fqn twice costs two
 * HTTP calls unless we dedupe.
 *
 * The cache also dedupes concurrent identical requests: two axes
 * racing the same path get the same in-flight Promise instead of
 * each holding its own semaphore slot. On a fan-out that revisits
 * the same targets (e.g. `@methods * @containingType | distinct
 * * @type` — same container type reached via multiple methods),
 * this can collapse dozens of redundant round-trips into one.
 *
 * qlang values are immutable at the language level so sharing a
 * single cached response across every caller is safe.
 *
 * Disable with JDT_GRAPH_CACHE=0 when you need to see fresh data
 * mid-session (rare; the process restarts between queries so by
 * default every `jdt q` invocation already has a cold cache).
 */
const GRAPH_CACHE_ENABLED =
    process.env.JDT_GRAPH_CACHE !== '0';

const requestCache = new Map();

async function fetchUncached(path) {
    await acquireSlot();
    try {
        return liftServerResponse(await get(path, GRAPH_TIMEOUT_MS));
    } finally {
        releaseSlot();
    }
}

function getEndpoint(path) {
    if (!GRAPH_CACHE_ENABLED) return fetchUncached(path);
    const cached = requestCache.get(path);
    if (cached) return cached;
    const promise = fetchUncached(path);
    requestCache.set(path, promise);
    return promise;
}

/**
 * Factory for axis operands that take subject from pipeValue and
 * call ?of=<fqn> on the named endpoint. Subject can be a Map (yields
 * its :fqn) or a string (used directly).
 */
function axisOp(name, endpointPath) {
    return nullaryOp(name, async (subject) => {
        const fqn = fqnOf(subject);
        if (fqn === null) return missingSubject(name, subject);
        return getEndpoint(`${endpointPath}?of=${enc(fqn)}`);
    });
}

// ── Nullary entry-points (no subject, no captured) ──────────────

const projectsImpl = nullaryOp('@projects',
        async () => getEndpoint('/projects'));

// ── Pattern search — subject IS the pattern (pipeline seed) ──────

const typesImpl = nullaryOp('@types', async (subject) => {
    if (typeof subject !== 'string') {
        return missingSubject('@types', subject);
    }
    return getEndpoint(`/types?pattern=${enc(subject)}`);
});

// ── Point lookups — subject is fqn-string or skeleton-node ───────

const typeImpl    = axisOp('@type',    '/type');
const methodImpl  = axisOp('@method',  '/method');
const fieldImpl   = axisOp('@field',   '/field');
const projectImpl = axisOp('@project', '/project');
const packageImpl = axisOp('@package', '/package');
const fileImpl    = axisOp('@file',    '/file');

// ── Down-navigation ─────────────────────────────────────────────

const membersImpl     = axisOp('@members',    '/members');
const methodsImpl     = axisOp('@methods',    '/methods');
const fieldsImpl      = axisOp('@fields',     '/fields');
const innerTypesImpl      = axisOp('@innerTypes',      '/innerTypes');
const typesInPackageImpl  = axisOp('@typesInPackage',  '/typesInPackage');
const typesInFileImpl     = axisOp('@typesInFile',     '/typesInFile');
const packagesInProjectImpl = axisOp('@packagesInProject', '/packagesInProject');
const supersImpl       = axisOp('@supers',       '/supers');
const subtypesImpl     = axisOp('@subtypes',     '/subtypes');
const implementorsImpl = axisOp('@implementors', '/implementors');
const overridesImpl    = axisOp('@overrides',    '/overrides');
const overloadsImpl    = axisOp('@overloads',    '/overloads');

// ── References — single endpoint, all kinds, no refKind filter ──

/**
 * Resolve a widening modifier passed to an overloaded axis. The
 * language parser hands the captured argument as a keyword value
 * (`:all`, `:call`, …); the server-side endpoint expects the raw
 * name string. Error values lifted into the modifier slot (e.g. a
 * prior failure on the fail-track) short-circuit to null so the
 * caller omits the query parameter rather than forwarding garbage.
 */
async function modifierName(modifierLambda, subject) {
    const modifierValue = await modifierLambda(subject);
    if (!isKeyword(modifierValue)) return null;
    return modifierValue.name;
}

/**
 * @refs widens via an optional refKind keyword:
 *     node | @refs              → server default for the subject kind
 *     node | @refs(:all)        → every refKind (call/read/write/typeUse)
 *     node | @refs(:call)       → call-sites only
 *     field | @refs(:write)     → writes only
 * Narrowing below the server default is always available in the
 * pipeline via `filter(/refKind | eq("…"))`.
 */
const refsImpl = overloadedOp('@refs', 2, {
    0: async (subject) => {
        const fqn = fqnOf(subject);
        if (fqn === null) return missingSubject('@refs', subject);
        return getEndpoint(`/refs?of=${enc(fqn)}`);
    },
    1: async (subject, refKindLambda) => {
        const fqn = fqnOf(subject);
        if (fqn === null) return missingSubject('@refs', subject);
        const refKind = await modifierName(refKindLambda, subject);
        const path = refKind !== null
            ? `/refs?of=${enc(fqn)}&refKind=${enc(refKind)}`
            : `/refs?of=${enc(fqn)}`;
        return getEndpoint(path);
    }
});

// ── Outgoing references — subject-member calls / reads / type-uses ──

const outgoingRefsImpl = axisOp('@outgoingRefs', '/outgoingRefs');

// ── Resources ───────────────────────────────────────────────────

const classpathImpl = axisOp('@classpath', '/classpath');
const sourceImpl    = axisOp('@source',    '/source');

/**
 * @problems accepts an optional scope keyword widening the marker
 * set the server walks:
 *     @problems            → workspace default
 *     @problems(:workspace) → explicit workspace scope
 *     @problems(:project)   → subject is a :project node or fqn
 *     @problems(:file)      → subject is a :file node or path
 * Scope is admissible as a modifier (normally narrowing is pipeline-
 * side) because `:workspace` on a giant project tree would be
 * prohibitively expensive as the unconditional default.
 */
const problemsImpl = overloadedOp('@problems', 2, {
    0: async () => getEndpoint('/problems'),
    1: async (subject, scopeLambda) => {
        const scope = await modifierName(scopeLambda, subject);
        if (scope === null || scope === 'workspace') {
            return getEndpoint('/problems');
        }
        const of = fqnOf(subject);
        if (of === null) return missingSubject('@problems', subject);
        if (scope === 'project') {
            return getEndpoint(`/problems?project=${enc(of)}`);
        }
        if (scope === 'file') {
            return getEndpoint(`/problems?file=${enc(of)}`);
        }
        return getEndpoint('/problems');
    }
});

// ── Locator factory ─────────────────────────────────────────────

export function createImpls() {
    return {
        '@projects':    projectsImpl,
        '@types':       typesImpl,
        '@type':        typeImpl,
        '@method':      methodImpl,
        '@field':       fieldImpl,
        '@project':     projectImpl,
        '@package':     packageImpl,
        '@file':        fileImpl,
        '@members':     membersImpl,
        '@methods':     methodsImpl,
        '@fields':      fieldsImpl,
        '@innerTypes':  innerTypesImpl,
        '@typesInPackage':     typesInPackageImpl,
        '@typesInFile':        typesInFileImpl,
        '@packagesInProject':  packagesInProjectImpl,
        '@supers':      supersImpl,
        '@subtypes':    subtypesImpl,
        '@implementors': implementorsImpl,
        '@overrides':   overridesImpl,
        '@overloads':   overloadsImpl,
        '@refs':        refsImpl,
        '@outgoingRefs': outgoingRefsImpl,
        '@source':      sourceImpl,
        '@classpath':   classpathImpl,
        '@problems':    problemsImpl
    };
}
