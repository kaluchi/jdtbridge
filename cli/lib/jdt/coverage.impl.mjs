// JS-side implementations for the :jdt/coverage qlang module.
//
// Two primitives, both thin HTTP wrappers:
//
//   @coverage           subject(node-Map | fqn) → /coverage/node Map
//   @activeCoverageId   () → String | null
//
// The 0-arity form of @coverage resolves the active coverageId via
// @activeCoverageId (per-process cached) and routes to
// /coverage/node?coverageId=<active>&fqn=<extracted>. The 1-arity
// overload pins the captured-arg coverageId — `@coverage("MyTest:1234")`
// or any sub-pipeline evaluating to a String — and bypasses the
// active lookup.
//
// Aggregations, predicates, line classification, card shaping —
// none live here. They are qlang conduits in coverage.qlang. The
// plugin is a thin adapter; this layer is a thin transport.

import { nullaryOp, overloadedOp } from '@kaluchi/qlang-core/dispatch';
import { keyword, makeErrorValue, isErrorValue }
    from '@kaluchi/qlang-core';
import { get } from '../../src/client.mjs';

const FQN_KEY = keyword('fqn');
const ACTIVE_COVERAGE_ID_KEY = keyword('activeCoverageId');

const enc = encodeURIComponent;

function envInt(name, fallback) {
    const override = Number.parseInt(process.env[name] ?? '', 10);
    return Number.isFinite(override) && override > 0
            ? override
            : fallback;
}

// HTTP timeout per coverage call. Override: JDT_COVERAGE_TIMEOUT_MS.
const COVERAGE_TIMEOUT_MS =
        envInt('JDT_COVERAGE_TIMEOUT_MS', 300_000);

// Per-process active-coverageId cache. One `/coverage/active` call
// per `jdt q` invocation regardless of how many coverage axes fire.
// Disable: JDT_COVERAGE_CACHE=0 (used in tests).
const COVERAGE_CACHE_ENABLED =
        process.env.JDT_COVERAGE_CACHE !== '0';

let activeCoverageIdPromise = null;

// Per-process /coverage/node cache keyed by `coverageId|fqn`.
// Coverage data is immutable for a given (session, fqn) pair, so a
// fan-out like `@typesInProject * @coverage` can dedupe repeats and
// concurrent identical in-flight requests.
const nodeCache = new Map();

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

function liftServerResponse(jsonVal) {
    if (jsonVal !== null
            && typeof jsonVal === 'object'
            && !Array.isArray(jsonVal)
            && jsonVal._error !== undefined) {
        return makeErrorValue(jsonToQlang(jsonVal._error));
    }
    return jsonToQlang(jsonVal);
}

function fqnOf(subject) {
    if (typeof subject === 'string') return subject;
    if (subject instanceof Map) {
        const fqn = subject.get(FQN_KEY);
        if (typeof fqn === 'string') return fqn;
    }
    return null;
}

function missingSubjectError(operandName, subject) {
    const ctx = new Map();
    ctx.set(keyword('operand'), operandName);
    ctx.set(keyword('subjectType'),
            subject === null || subject === undefined ? 'null'
            : Array.isArray(subject) ? 'vec'
            : subject instanceof Map ? 'map-without-fqn'
            : typeof subject);
    const descriptor = new Map();
    descriptor.set(keyword('kind'),
            keyword('missing-subject-fqn'));
    descriptor.set(keyword('thrown'), keyword('MissingSubjectFqn'));
    descriptor.set(keyword('origin'), keyword('jdt/coverage'));
    descriptor.set(keyword('message'),
            `${operandName}: pipeValue must be a node-Map with `
            + `:fqn or a fqn-String`);
    descriptor.set(keyword('context'), ctx);
    return makeErrorValue(descriptor);
}

function noActiveCoverageError() {
    const descriptor = new Map();
    descriptor.set(keyword('kind'),
            keyword('coverage-no-active-session'));
    descriptor.set(keyword('thrown'),
            keyword('CoverageNoActiveSession'));
    descriptor.set(keyword('origin'), keyword('jdt/coverage'));
    descriptor.set(keyword('message'),
            'No active coverage session — pin a coverageId via '
            + '`@coverage("<coverageId>")` or activate one via '
            + '`jdt coverage activate <id>`');
    return makeErrorValue(descriptor);
}

function coverageIdNotStringError(actualValue) {
    const descriptor = new Map();
    descriptor.set(keyword('kind'),
            keyword('coverage-id-not-string'));
    descriptor.set(keyword('thrown'),
            keyword('CoverageIdNotString'));
    descriptor.set(keyword('origin'), keyword('jdt/coverage'));
    descriptor.set(keyword('message'),
            `@coverage modifier must evaluate to a non-empty `
            + `String coverageId, got `
            + (actualValue === null ? 'null' : typeof actualValue));
    return makeErrorValue(descriptor);
}

async function fetchActiveCoverageId() {
    const raw = await get(
            '/coverage/active', COVERAGE_TIMEOUT_MS);
    const lifted = liftServerResponse(raw);
    if (isErrorValue(lifted)) return lifted;
    if (lifted instanceof Map) {
        const id = lifted.get(ACTIVE_COVERAGE_ID_KEY);
        return typeof id === 'string' ? id : null;
    }
    return null;
}

function getActiveCoverageId() {
    if (!COVERAGE_CACHE_ENABLED) return fetchActiveCoverageId();
    if (activeCoverageIdPromise) return activeCoverageIdPromise;
    activeCoverageIdPromise = fetchActiveCoverageId();
    activeCoverageIdPromise.catch(() => {
        activeCoverageIdPromise = null;
    });
    return activeCoverageIdPromise;
}

async function fetchNodeUncached(coverageId, fqn) {
    return liftServerResponse(await get(
            `/coverage/node?coverageId=${enc(coverageId)}`
            + `&fqn=${enc(fqn)}`,
            COVERAGE_TIMEOUT_MS));
}

function fetchNode(coverageId, fqn) {
    if (!COVERAGE_CACHE_ENABLED) {
        return fetchNodeUncached(coverageId, fqn);
    }
    const key = coverageId + '|' + fqn;
    const cached = nodeCache.get(key);
    if (cached) return cached;
    const promise = fetchNodeUncached(coverageId, fqn);
    promise.catch(() => nodeCache.delete(key));
    nodeCache.set(key, promise);
    return promise;
}

const activeCoverageIdImpl = nullaryOp('@activeCoverageId',
        async () => getActiveCoverageId());

const coverageImpl = overloadedOp('@coverage', 2, {
    0: async (subject) => {
        const fqn = fqnOf(subject);
        if (fqn === null) {
            return missingSubjectError('@coverage', subject);
        }
        const id = await getActiveCoverageId();
        if (isErrorValue(id)) return id;
        if (id === null || id === undefined) {
            return noActiveCoverageError();
        }
        return fetchNode(id, fqn);
    },
    1: async (subject, coverageIdLambda) => {
        const fqn = fqnOf(subject);
        if (fqn === null) {
            return missingSubjectError('@coverage', subject);
        }
        const id = await coverageIdLambda(subject);
        if (isErrorValue(id)) return id;
        if (typeof id !== 'string' || id.length === 0) {
            return coverageIdNotStringError(id);
        }
        return fetchNode(id, fqn);
    },
});

export function createImpls() {
    return {
        '@coverage':         coverageImpl,
        '@activeCoverageId': activeCoverageIdImpl,
    };
}
