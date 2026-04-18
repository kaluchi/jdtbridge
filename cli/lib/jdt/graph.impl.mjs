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

import { nullaryOp } from '@kaluchi/qlang-core/dispatch';
import { keyword, makeErrorValue } from '@kaluchi/qlang-core';
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

async function getEndpoint(path) {
    return liftServerResponse(await get(path, 30_000));
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

const refsImpl = axisOp('@refs', '/refs');

// ── Resources ───────────────────────────────────────────────────

const classpathImpl = axisOp('@classpath', '/classpath');
const sourceImpl    = axisOp('@source',    '/source');

// ── Problems — workspace-wide, scope via filter on qlang side ───

const problemsImpl = nullaryOp('@problems',
        async () => getEndpoint('/problems'));

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
        '@source':      sourceImpl,
        '@classpath':   classpathImpl,
        '@problems':    problemsImpl
    };
}
