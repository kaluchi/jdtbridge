// JS-side implementations for the :jdt/graph qlang module.
//
// Every operand is a thin async wrapper over a plugin HTTP endpoint
// that emits canonical-shape JSON (skeletons or detail nodes) or
// {"_error": {…}} on failure. This module:
//   1. Resolves subject — accepts a node-Map (skeleton or detail) or
//      a fqn/fqmn String. Map subjects yield their :fqn field.
//      Each operand is invokable as both `subject | @op` (subject
//      from pipeValue) and `@op(subject)` (subject as captured arg)
//      via the uniform navOp / rootOp dispatcher.
//   2. Calls the corresponding endpoint with URL-encoded params.
//   3. Detects the {"_error": {…}} discriminator and lifts the
//      structured descriptor into a qlang Error value via
//      makeErrorValue, which then deflects through | / * / >>
//      and fires under !|.
//
// Subject polymorphism is the discipline that makes the operand
// catalog uniformly composable: every chain `node | @axis * @axis`
// works because each axis pulls the identifier out of whatever
// shape arrived.

import {
    overloadedOp
} from '@kaluchi/qlang-core/dispatch';
import {
    keyword,
    makeErrorValue
} from '@kaluchi/qlang-core';
import { get } from '../../src/client.mjs';

// ── Conversion helpers ──────────────────────────────────────────

const FQN_KEY = keyword('fqn');
const CONTAINING_PROJECT_KEY = keyword('containingProject');

/**
 * Recursively convert plain JSON (from HTTP) into qlang shape.
 * Objects become Maps with interned keyword keys; arrays become Vecs
 * (qlang treats arrays as Vecs natively); scalars pass through.
 */
function jsonToQlang(jsonVal) {
    if (Array.isArray(jsonVal)) {
        return jsonVal.map(jsonToQlang);
    }
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
 * Pull the canonical identifier out of whatever subject arrived.
 * Map → :fqn projection. String → returned as-is. Anything else
 * yields null (caller raises a qlang error).
 */
function subjectToIdentifier(subject) {
    if (typeof subject === 'string') return subject;
    if (subject instanceof Map) {
        const fqn = subject.get(FQN_KEY);
        if (typeof fqn === 'string') return fqn;
    }
    return null;
}

/**
 * Inspect a plugin response. {"_error": …} → qlang Error value;
 * anything else → qlang-converted data.
 */
function liftServerResponse(jsonVal) {
    if (jsonVal !== null
            && typeof jsonVal === 'object'
            && !Array.isArray(jsonVal)
            && jsonVal._error !== undefined) {
        const descriptor = jsonToQlang(jsonVal._error);
        return makeErrorValue(descriptor);
    }
    return jsonToQlang(jsonVal);
}

function missingFqnError(operandName, subject) {
    const ctx = new Map();
    ctx.set(keyword('operand'), operandName);
    ctx.set(keyword('subjectType'),
            subject === null || subject === undefined ? 'null'
            : Array.isArray(subject) ? 'vec'
            : subject instanceof Map ? 'map'
            : typeof subject);
    const descriptor = new Map();
    descriptor.set(keyword('kind'), keyword('missing-subject-fqn'));
    descriptor.set(keyword('thrown'), keyword('MissingSubjectFqn'));
    descriptor.set(keyword('origin'), keyword('jdt/graph'));
    descriptor.set(keyword('message'),
            `${operandName}: subject has no :fqn — pass a node-Map or fqn-String`);
    descriptor.set(keyword('context'), ctx);
    return makeErrorValue(descriptor);
}

const enc = encodeURIComponent;

async function getEndpoint(path) {
    return liftServerResponse(await get(path, 30_000));
}

/**
 * Build a navigational operand that accepts subject from EITHER
 * pipeValue OR a captured argument. Both forms map to the same
 * endpoint?of=<identifier> shape. Modifier slots (refKind etc.)
 * are handled by per-operand factories below.
 */
function navOp(name, endpointPath) {
    return overloadedOp(name, 1, {
        0: async (subject) => {
            const id = subjectToIdentifier(subject);
            if (id === null) return missingFqnError(name, subject);
            return getEndpoint(`${endpointPath}?of=${enc(id)}`);
        },
        1: async (pipeValue, subjectLambda) => {
            const subject = await subjectLambda(pipeValue);
            const id = subjectToIdentifier(subject);
            if (id === null) return missingFqnError(name, subject);
            return getEndpoint(`${endpointPath}?of=${enc(id)}`);
        }
    });
}

// ── Root queries ────────────────────────────────────────────────

const typesImpl = overloadedOp('@types', 2, {
    0: async (subject) => {
        if (typeof subject !== 'string') {
            return missingFqnError('@types', subject);
        }
        return getEndpoint(`/types?pattern=${enc(subject)}`);
    },
    1: async (pipeValue, patternLambda) => {
        const pattern = await patternLambda(pipeValue);
        if (typeof pattern !== 'string') {
            return missingFqnError('@types', pattern);
        }
        return getEndpoint(`/types?pattern=${enc(pattern)}`);
    },
    2: async (pipeValue, patternLambda, flagLambda) => {
        const pattern = await patternLambda(pipeValue);
        const flagVal = await flagLambda(pipeValue);
        const sourceOnly = flagVal && flagVal.name === 'sourceOnly';
        if (typeof pattern !== 'string') {
            return missingFqnError('@types', pattern);
        }
        let url = `/types?pattern=${enc(pattern)}`;
        if (sourceOnly) url += '&sourceOnly';
        return getEndpoint(url);
    }
});

const typeImpl    = navOp('@type',    '/type');
const methodImpl  = navOp('@method',  '/method');
const fieldImpl   = navOp('@field',   '/field');
const projectImpl = navOp('@project', '/project');
const packageImpl = navOp('@package', '/package');
const fileImpl    = navOp('@file',    '/file');

const projectsImpl = overloadedOp('@projects', 0, {
    0: async () => getEndpoint('/projects2')
});

// ── Detail enrichment ───────────────────────────────────────────

const detailImpl = navOp('@detail', '/detail');

// ── Containment ─────────────────────────────────────────────────

const containingTypeImpl = overloadedOp('@containingType', 1, {
    0: async (subject) => containingTypeBody(subject, '@containingType'),
    1: async (pipeValue, subjectLambda) =>
        containingTypeBody(await subjectLambda(pipeValue),
                '@containingType')
});

async function containingTypeBody(subject, opName) {
    const id = subjectToIdentifier(subject);
    if (id === null) return missingFqnError(opName, subject);
    const hash = id.indexOf('#');
    if (hash >= 0) {
        return getEndpoint(`/type?of=${enc(id.substring(0, hash))}`);
    }
    // Subject is already a type — use /type detail's :containingType
    const raw = await get(`/type?of=${enc(id)}`, 30_000);
    if (raw && raw._error) {
        return makeErrorValue(jsonToQlang(raw._error));
    }
    if (raw && raw.containingType) {
        return getEndpoint(`/type?of=${enc(raw.containingType)}`);
    }
    return null;
}

const containingProjectImpl = overloadedOp('@containingProject', 1, {
    0: async (subject) =>
        containingProjectBody(subject, '@containingProject'),
    1: async (pipeValue, subjectLambda) =>
        containingProjectBody(await subjectLambda(pipeValue),
                '@containingProject')
});

async function containingProjectBody(subject, opName) {
    if (subject instanceof Map) {
        const proj = subject.get(CONTAINING_PROJECT_KEY);
        if (typeof proj === 'string') {
            return getEndpoint(`/project?of=${enc(proj)}`);
        }
    }
    return missingFqnError(opName, subject);
}

// ── Down-navigation ─────────────────────────────────────────────

const membersImpl       = navOp('@members',       '/members');
const methodsImpl       = navOp('@methods',       '/methods');
const fieldsImpl        = navOp('@fields',        '/fields');
const innerTypesImpl    = navOp('@innerTypes',    '/innerTypes');
const typesInPackageImpl = navOp('@typesInPackage',
        '/typesInPackage');
const typesInFileImpl   = navOp('@typesInFile',   '/typesInFile');
const packagesInProjectImpl = navOp('@packagesInProject',
        '/packagesInProject');

// ── Hierarchy ───────────────────────────────────────────────────

const supersImpl       = navOp('@supers',       '/supers');
const subtypesImpl     = navOp('@subtypes',     '/subtypes');
const implementorsImpl = navOp('@implementors', '/implementors2');
const overridesImpl    = navOp('@overrides',    '/overrides');
const overloadsImpl    = navOp('@overloads',    '/overloads');

// ── References ──────────────────────────────────────────────────

const REFKIND_NAMES =
        new Set(['call', 'read', 'write', 'typeUse', 'all']);

const refsImpl = overloadedOp('@refs', 2, {
    0: async (subject) => {
        const id = subjectToIdentifier(subject);
        if (id === null) return missingFqnError('@refs', subject);
        return getEndpoint(`/refs?of=${enc(id)}`);
    },
    1: async (pipeValue, argLambda) => {
        // Two surface shapes share captured-arg=1:
        //   subject | @refs(:call)        — captured = refKind keyword
        //   @refs(node-or-fqn)            — captured = subject
        // Disambiguate by inspecting the captured value.
        const argVal = await argLambda(pipeValue);
        if (argVal && typeof argVal === 'object'
                && argVal.name && REFKIND_NAMES.has(argVal.name)) {
            const id = subjectToIdentifier(pipeValue);
            if (id === null) return missingFqnError('@refs', pipeValue);
            return getEndpoint(
                `/refs?of=${enc(id)}&refKind=${enc(argVal.name)}`);
        }
        const id = subjectToIdentifier(argVal);
        if (id === null) return missingFqnError('@refs', argVal);
        return getEndpoint(`/refs?of=${enc(id)}`);
    },
    2: async (pipeValue, subjectLambda, refKindLambda) => {
        const subject = await subjectLambda(pipeValue);
        const refKindVal = await refKindLambda(pipeValue);
        const refKind = refKindVal && refKindVal.name
                ? refKindVal.name : 'all';
        const id = subjectToIdentifier(subject);
        if (id === null) return missingFqnError('@refs', subject);
        return getEndpoint(
            `/refs?of=${enc(id)}&refKind=${enc(refKind)}`);
    }
});

// ── Resources ───────────────────────────────────────────────────

const classpathImpl = navOp('@classpath', '/classpath');

// ── Source text ─────────────────────────────────────────────────

const sourceImpl = navOp('@source', '/source2');

// ── Problems ────────────────────────────────────────────────────

const problemsImpl = overloadedOp('@problems', 1, {
    0: async () => getEndpoint('/problems2'),
    1: async (pipeValue, scopeLambda) => {
        const scopeVal = await scopeLambda(pipeValue);
        if (typeof scopeVal === 'string') {
            if (scopeVal.includes('/') || scopeVal.includes('\\')) {
                return getEndpoint(`/problems2?file=${enc(scopeVal)}`);
            }
            return getEndpoint(`/problems2?project=${enc(scopeVal)}`);
        }
        if (scopeVal && scopeVal.name) {
            return getEndpoint(`/problems2?${enc(scopeVal.name)}`);
        }
        return getEndpoint('/problems2');
    }
});

// ── Sugar: callers / readers / writers ──────────────────────────
// Each is a two-form operand: `subject | @callers` (pipeValue)
// AND `@callers(subject)` (captured). Returns the :from skeleton
// nodes only — for the full reference edges, use @refs directly.

const FROM_KEY = keyword('from');

function sugarRefsOp(name, refKind) {
    return overloadedOp(name, 1, {
        0: async (subject) => {
            const id = subjectToIdentifier(subject);
            if (id === null) return missingFqnError(name, subject);
            return extractFrom(
                await getEndpoint(
                    `/refs?of=${enc(id)}&refKind=${enc(refKind)}`));
        },
        1: async (pipeValue, subjectLambda) => {
            const subject = await subjectLambda(pipeValue);
            const id = subjectToIdentifier(subject);
            if (id === null) return missingFqnError(name, subject);
            return extractFrom(
                await getEndpoint(
                    `/refs?of=${enc(id)}&refKind=${enc(refKind)}`));
        }
    });
}

function extractFrom(refs) {
    if (!Array.isArray(refs)) return refs;
    return refs
        .map(ref => ref instanceof Map ? ref.get(FROM_KEY) : null)
        .filter(from => from !== null);
}

const callersImpl = sugarRefsOp('@callers', 'call');
const readersImpl = sugarRefsOp('@readers', 'read');
const writersImpl = sugarRefsOp('@writers', 'write');

// ── Locator factory ─────────────────────────────────────────────

export function createImpls() {
    return {
        '@types':       typesImpl,
        '@type':        typeImpl,
        '@method':      methodImpl,
        '@field':       fieldImpl,
        '@project':     projectImpl,
        '@projects':    projectsImpl,
        '@package':     packageImpl,
        '@file':        fileImpl,
        '@detail':      detailImpl,
        '@containingType':    containingTypeImpl,
        '@containingProject': containingProjectImpl,
        '@members':     membersImpl,
        '@methods':     methodsImpl,
        '@fields':      fieldsImpl,
        '@innerTypes':  innerTypesImpl,
        '@typesInPackage':    typesInPackageImpl,
        '@typesInFile':       typesInFileImpl,
        '@packagesInProject': packagesInProjectImpl,
        '@supers':      supersImpl,
        '@subtypes':    subtypesImpl,
        '@implementors': implementorsImpl,
        '@overrides':   overridesImpl,
        '@overloads':   overloadsImpl,
        '@refs':        refsImpl,
        '@classpath':   classpathImpl,
        '@source':      sourceImpl,
        '@problems':    problemsImpl,
        '@callers':     callersImpl,
        '@readers':     readersImpl,
        '@writers':     writersImpl
    };
}
