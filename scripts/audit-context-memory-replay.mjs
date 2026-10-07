import { readFile, readdir, lstat } from 'node:fs/promises';
import { resolve, relative, sep } from 'node:path';
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';

const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const json = async path => JSON.parse(await readFile(path, 'utf8'));
const inside = (root, path) => path.startsWith(root + sep);

function pointer(value, path) {
  if (path === '') return value;
  if (!path.startsWith('/')) throw new Error('invalid JSON pointer');
  return path.slice(1).split('/').reduce((node, part) => node?.[part.replaceAll('~1', '/').replaceAll('~0', '~')], value);
}

function matches(actual, relation, expected) {
  if (actual === undefined) return false;
  if (relation === 'equals') return isDeepStrictEqual(actual, expected);
  const contains = Array.isArray(actual) ? actual.some(item => isDeepStrictEqual(item, expected))
    : typeof actual === 'string' && typeof expected === 'string' && actual.includes(expected);
  if (relation === 'contains') return contains;
  if (relation === 'not-contains') return !contains;
  throw new Error('unknown scorer relation');
}

function retrievalMetrics(ranked, k, alternatives) {
  const returned = [...new Set(ranked)].slice(0, k);
  if (alternatives.length === 0) return { uniqueReturned: returned.length, sourceRecall: null,
    allEvidenceHit: null, anyEvidenceHit: null, precision: null, unanswerable: true, emptyRetrieval: returned.length === 0 };
  const relevant = new Set(alternatives.flat());
  const hits = returned.filter(source => relevant.has(source)).length;
  return { uniqueReturned: returned.length,
    sourceRecall: Math.max(...alternatives.map(required => required.filter(source => returned.includes(source)).length / required.length)),
    allEvidenceHit: alternatives.some(required => required.every(source => returned.includes(source))),
    anyEvidenceHit: hits > 0, precision: returned.length === 0 ? null : hits / returned.length,
    unanswerable: false, emptyRetrieval: returned.length === 0 };
}

function usageTotals(entries) {
  const actual = entries.filter(entry => entry.usage.source === 'ACTUAL');
  const unavailableCalls = entries.filter(entry => entry.usage.source === 'UNAVAILABLE').length;
  const sum = field => actual.length ? actual.reduce((total, entry) => total + entry.usage[field], 0) : null;
  return { calls: entries.length, failedCalls: entries.filter(entry => entry.failureType !== null).length,
    actualCalls: actual.length, unavailableCalls, estimatedCalls: entries.length - actual.length - unavailableCalls,
    actualInputTokens: sum('promptTokens'), actualOutputTokens: sum('completionTokens'), actualTotalTokens: sum('totalTokens'),
    cacheHitInputTokens: sum('promptCacheHitTokens'), cacheMissInputTokens: sum('promptCacheMissTokens'),
    reasoningOutputTokens: sum('reasoningTokens'), completeActualUsage: entries.length > 0 && actual.length === entries.length };
}

async function artifactHashes(root) {
  const hashes = {};
  const walk = async dir => {
    for (const name of (await readdir(dir)).sort()) {
      const path = resolve(dir, name);
      const stat = await lstat(path);
      if (stat.isSymbolicLink()) throw new Error('artifact symlinks are not supported');
      if (stat.isDirectory()) await walk(path);
      else if (stat.isFile()) {
        const key = relative(root, path).split(sep).join('/');
        if (key !== 'artifact-hashes.json') hashes[key] = hash(await readFile(path));
      }
    }
  };
  await walk(root);
  return hashes;
}

/** Second implementation: reads raw evidence and gold, never runner scores or summary. Does not mutate outputs. */
export async function auditReplay(output) {
  const root = resolve(output);
  const frozenFile = resolve(root, 'frozen-cases.json');
  const specs = await json(frozenFile);
  const manifest = await json(resolve(root, 'manifest.json'));
  const cases = [];
  for (const spec of specs) {
    if (!/^[a-z0-9-]+$/.test(spec.id)) throw new Error('invalid case ID');
    const instance = resolve(root, 'instances', spec.id);
    const observation = await json(resolve(instance, 'observation.json'));
    const checks = [];
    for (const check of spec.gold.checks) {
      const file = resolve(instance, check.artifact);
      if (!inside(instance, file)) throw new Error('scorer path escape');
      const passed = matches(pointer(await json(file), check.pointer), check.relation, check.expected);
      checks.push({ pointer: check.pointer, passed, failureType: passed ? null : 'MECHANISM_CONTRACT_MISMATCH' });
    }
    const usage = usageTotals(await json(resolve(instance, 'usage.json')));
    const retrieval = ['MEMORY', 'FRESHNESS'].includes(spec.operation)
      ? retrievalMetrics(observation.retrievedSources ?? [], spec.gold.retrievalK, spec.gold.evidenceAlternatives) : null;
    const executionFailure = observation.executionFailure ?? null;
    cases.push({ id: spec.id, family: spec.family, mechanismPassed: executionFailure === null && checks.every(check => check.passed),
      checks, retrieval, usage, executionFailure });
  }
  const storedHashes = await json(resolve(root, 'artifact-hashes.json'));
  const currentHashes = await artifactHashes(root);
  const frozenMatches = manifest.datasetHash === hash(await readFile(frozenFile));
  const sourceUnchanged = (await json(resolve(root, 'source-integrity.json'))).unchanged === true;
  return { artifactIntegrity: isDeepStrictEqual(storedHashes, currentHashes) && frozenMatches && sourceUnchanged,
    attemptedCases: cases.length, mechanismPassed: cases.filter(item => item.mechanismPassed).length, cases,
    effectClaims: 'MECHANISM_REPLAY_ONLY_NO_LIVE_COMPLETION_OR_TOKEN_SAVINGS_CLAIM' };
}
