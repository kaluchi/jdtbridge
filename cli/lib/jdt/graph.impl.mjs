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
import { remapJsonPaths } from '../../src/json-output.mjs';
import { translateHostPathFromLocal } from '../../src/path-translate.mjs';

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

function envInt(name, fallback) {
    const override = Number.parseInt(process.env[name] ?? '', 10);
    return Number.isFinite(override) && override > 0
        ? override
        : fallback;
}

// HTTP timeout per axis call. Override: JDT_GRAPH_TIMEOUT_MS.
const GRAPH_TIMEOUT_MS = envInt('JDT_GRAPH_TIMEOUT_MS', 300_000);

// Max simultaneous in-flight HTTP calls. Override: JDT_GRAPH_CONCURRENCY.
// qlang `*` fans out through Promise.all; without a ceiling a deep
// fan-out like `@projects * @members * @methods` drowns Eclipse's
// workspace lock and starves the CLI past its timeout.
const GRAPH_CONCURRENCY = envInt('JDT_GRAPH_CONCURRENCY', 8);

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

// Per-process cache keyed by the full endpoint path. Dedupes both
// repeat lookups and concurrent identical in-flight requests.
// Disable: JDT_GRAPH_CACHE=0.
const GRAPH_CACHE_ENABLED =
    process.env.JDT_GRAPH_CACHE !== '0';

const requestCache = new Map();

async function fetchUncached(path) {
    await acquireSlot();
    try {
        const raw = await get(path, GRAPH_TIMEOUT_MS);
        return liftServerResponse(remapJsonPaths(raw));
    } finally {
        releaseSlot();
    }
}

function getEndpoint(path) {
    if (!GRAPH_CACHE_ENABLED) return fetchUncached(path);
    const cached = requestCache.get(path);
    if (cached) return cached;
    const promise = fetchUncached(path);
    promise.catch(() => requestCache.delete(path));
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

/**
 * Axis factory for endpoints whose {@code of=} parameter is a
 * filesystem path (i.e. {@code @file}, {@code @typesInFile}). The
 * plugin only accepts Eclipse-host paths, but a subject that rides
 * in from a prior node's `:location/:file` has already been
 * translated to CLI-local form by {@code remapJsonPaths}. Reverse-
 * translate here before the RPC so the pipeline chain
 * {@code node | /location/file | @file} works in remote mode.
 * No-op on local instances.
 */
function pathAxisOp(name, endpointPath) {
    return nullaryOp(name, async (subject) => {
        const fqn = fqnOf(subject);
        if (fqn === null) return missingSubject(name, subject);
        const hostPath = translateHostPathFromLocal(fqn);
        return getEndpoint(`${endpointPath}?of=${enc(hostPath)}`);
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
const fileImpl    = pathAxisOp('@file', '/file');

// ── Down-navigation ─────────────────────────────────────────────

const membersImpl     = axisOp('@members',    '/members');
const methodsImpl     = axisOp('@methods',    '/methods');
const fieldsImpl      = axisOp('@fields',     '/fields');
const innerTypesImpl      = axisOp('@innerTypes',      '/innerTypes');
const typesInPackageImpl  = axisOp('@typesInPackage',  '/typesInPackage');
const typesInFileImpl     = pathAxisOp('@typesInFile', '/typesInFile');
const packagesInProjectImpl = axisOp('@packagesInProject', '/packagesInProject');
const supersImpl       = axisOp('@supers',       '/supers');
const subtypesImpl     = axisOp('@subtypes',     '/subtypes');
const implementorsImpl = axisOp('@implementors', '/implementors');
const overridesImpl    = axisOp('@overrides',    '/overrides');
const overloadsImpl    = axisOp('@overloads',    '/overloads');

// ── References — single endpoint, all kinds, no refKind filter ──

// Modifier keyword → raw name string; non-keyword → null.
async function modifierName(modifierLambda, subject) {
    const modifierValue = await modifierLambda(subject);
    return isKeyword(modifierValue) ? modifierValue.name : null;
}

// @incomingRefs(:refKind?) — optional refKind widens the search.
async function fetchIncomingRefs(subject, refKind) {
    const fqn = fqnOf(subject);
    if (fqn === null) return missingSubject('@incomingRefs', subject);
    const suffix = refKind !== null ? `&refKind=${enc(refKind)}` : '';
    return getEndpoint(`/refs?of=${enc(fqn)}${suffix}`);
}

const incomingRefsImpl = overloadedOp('@incomingRefs', 2, {
    0: (subject) => fetchIncomingRefs(subject, null),
    1: async (subject, refKindLambda) =>
        fetchIncomingRefs(subject,
            await modifierName(refKindLambda, subject))
});

// ── Outgoing references — subject-member calls / reads / type-uses ──

const outgoingRefsImpl = axisOp('@outgoingRefs', '/outgoingRefs');

// ── Resources ───────────────────────────────────────────────────

const classpathImpl = axisOp('@classpath', '/classpath');
const sourceImpl    = axisOp('@source',    '/source');

// @problems(:scope?) — :workspace (default) / :project / :file.
const PROBLEMS_SCOPE_PARAM = { project: 'project', file: 'file' };

async function fetchProblems(subject, scope) {
    const param = PROBLEMS_SCOPE_PARAM[scope];
    if (!param) return getEndpoint('/problems');
    const subjectFqn = fqnOf(subject);
    if (subjectFqn === null) return missingSubject('@problems', subject);
    return getEndpoint(`/problems?${param}=${enc(subjectFqn)}`);
}

const problemsImpl = overloadedOp('@problems', 2, {
    0: () => getEndpoint('/problems'),
    1: async (subject, scopeLambda) =>
        fetchProblems(subject,
            await modifierName(scopeLambda, subject))
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
        '@incomingRefs': incomingRefsImpl,
        '@outgoingRefs': outgoingRefsImpl,
        '@source':      sourceImpl,
        '@classpath':   classpathImpl,
        '@problems':    problemsImpl
    };
}
