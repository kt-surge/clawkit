import { readFile, readdir, lstat } from 'node:fs/promises';
import { resolve, relative, sep } from 'node:path';
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';

const requireThat = (condition, message) => { if (!condition) throw new Error(message); };
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const json = async file => JSON.parse(await readFile(file, 'utf8'));
const lines = async file => (await readFile(file, 'utf8')).split(/\r?\n/).filter(line => line.trim()).map(line => JSON.parse(line));
const C = ['C1_ROLLING_SUMMARY', 'C2_FROZEN_ORIGINAL_CONTEXT', 'C3_CANDIDATE_CONTEXT'];
const M = ['M0_NO_HISTORY', 'M1_FULL_ARCHIVE_READ_GREP', 'M2_NATIVE_MEMORY_SESSION'];
const integer = value => Number.isSafeInteger(value) && value >= 0;
const mean = values => values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
const sum = values => values.reduce((a, b) => a + b, 0);

// A separate implementation of java.util.Random/Collections.shuffle, solely for the frozen order contract.
export function frozenOrder(data) {
  requireThat(data.split === 'FROZEN_HOLDOUT' && data.repetitions === 3 && data.tasks.length === 24,
    '24 heldout tasks with three repetitions required');
  requireThat(data.plannedTasks === 24 && data.plannedInstances === 216, 'frozen denominator differs');
  const ids = new Set(data.tasks.map(task => task.id));
  requireThat(ids.size === 24 && [...ids].every(id => /^[a-z][a-z0-9-]{0,80}$/.test(id)), 'unique safe task IDs required');
  let seed = (BigInt(data.seed) ^ 0x5deece66dn) & ((1n << 48n) - 1n);
  const next = bits => { seed = (seed * 0x5deece66dn + 11n) & ((1n << 48n) - 1n); return Number(seed >> BigInt(48 - bits)); };
  const nextInt = bound => {
    if ((bound & -bound) === bound) return Number((BigInt(bound) * BigInt(next(31))) >> 31n);
    let bits, value;
    do { bits = next(31); value = bits % bound; } while (bits - value + bound - 1 > 2147483647);
    return value;
  };
  const result = [];
  for (let repetition = 1; repetition <= 3; repetition++) {
    for (const [suite, arms] of [['COMPRESSION', C], ['MEMORY', M]]) {
      const tasks = data.tasks.filter(task => task.suite === suite);
      requireThat(tasks.length === 12 && isDeepStrictEqual(data.suites[suite], arms), 'suite contract differs');
      const families = new Map();
      for (const task of tasks) families.set(task.family, (families.get(task.family) ?? 0) + 1);
      requireThat(families.size === 4 && [...families.values()].every(count => count === 3), 'four families with three tasks required');
      for (let i = tasks.length; i > 1; i--) { const j = nextInt(i); [tasks[i - 1], tasks[j]] = [tasks[j], tasks[i - 1]]; }
      for (let i = 0; i < tasks.length; i++) for (let j = 0; j < 3; j++) {
        const arm = arms[(i + j + repetition - 1) % 3];
        result.push({ id: tasks[i].id + '-' + arm.toLowerCase() + '-r' + repetition, taskId: tasks[i].id, suite, arm, repetition });
      }
    }
  }
  return result;
}

export function usageTotals(entries) {
  const actual = entries.filter(entry => entry.usage.source === 'ACTUAL');
  for (const entry of entries) {
    requireThat(entry.callId && integer(entry.durationMs) && ['ACTUAL', 'UNAVAILABLE', 'ESTIMATED'].includes(entry.usage.source), 'invalid usage entry');
    for (const field of ['promptTokens', 'completionTokens', 'totalTokens', 'promptCacheHitTokens', 'promptCacheMissTokens', 'reasoningTokens'])
      requireThat(integer(entry.usage[field]), 'invalid usage count');
    requireThat(entry.usage.totalTokens === entry.usage.promptTokens + entry.usage.completionTokens, 'input/output usage does not add up');
  }
  const total = field => actual.length ? sum(actual.map(entry => entry.usage[field])) : null;
  return { calls: entries.length, failedCalls: entries.filter(entry => entry.failureType != null).length,
    actualCalls: actual.length, unavailableCalls: entries.filter(entry => entry.usage.source === 'UNAVAILABLE').length,
    estimatedCalls: entries.filter(entry => entry.usage.source === 'ESTIMATED').length,
    actualInputTokens: total('promptTokens'), actualOutputTokens: total('completionTokens'), actualTotalTokens: total('totalTokens'),
    cacheHitInputTokens: total('promptCacheHitTokens'), cacheMissInputTokens: total('promptCacheMissTokens'), reasoningOutputTokens: total('reasoningTokens'),
    completeActualUsage: entries.length > 0 && entries.every(entry => entry.usage.source === 'ACTUAL') };
}

async function artifactHashes(root) {
  const values = {};
  async function walk(directory) {
    for (const name of (await readdir(directory)).sort()) {
      const file = resolve(directory, name), stat = await lstat(file);
      requireThat(!stat.isSymbolicLink(), 'artifact symlink refused');
      if (stat.isDirectory()) await walk(file);
      else if (stat.isFile()) { const key = relative(root, file).split(sep).join('/'); if (key !== 'artifact-hashes.json') values[key] = hash(await readFile(file)); }
    }
  }
  await walk(root); return values;
}

function generator(seed) {
  let state = seed >>> 0;
  return () => { state += 0x6d2b79f5; let value = state; value = Math.imul(value ^ value >>> 15, value | 1);
    value ^= value + Math.imul(value ^ value >>> 7, value | 61); return ((value ^ value >>> 14) >>> 0) / 4294967296; };
}
function interval(values, units) {
  if (!values.length) return { estimate: null, lower95: null, upper95: null, units: 0 };
  const random = generator(20261004), samples = [];
  for (let i = 0; i < 5000; i++) { const sample = []; for (let j = 0; j < values.length; j++) sample.push(values[Math.floor(random() * values.length)]); samples.push(mean(sample)); }
  samples.sort((a, b) => a - b);
  return { estimate: mean(values), lower95: samples[Math.floor(.025 * (samples.length - 1))], upper95: samples[Math.ceil(.975 * (samples.length - 1))],
    units: values.length, unit: units, draws: 5000, seed: 20261004, interval: 'PERCENTILE_BOOTSTRAP_PAIRED_BLOCKS' };
}

/** Repetitions stay inside each task block; a family sensitivity uses four family blocks. */
export function pairedCompletion(rows, tasks, candidate, baseline) {
  const blocks = tasks.map(task => {
    const a = rows.filter(row => row.taskId === task.id && row.arm === candidate);
    const b = rows.filter(row => row.taskId === task.id && row.arm === baseline);
    requireThat(a.length === 3 && b.length === 3 && new Set(a.map(row => row.repetition)).size === 3 && new Set(b.map(row => row.repetition)).size === 3,
      'three paired repetitions per task required');
    const complete = [...a, ...b].every(row => row.status !== 'NOT_RUN');
    return { taskId: task.id, family: task.family, complete, difference: complete ? mean(a.map(row => row.status === 'PASS' ? 1 : 0)) - mean(b.map(row => row.status === 'PASS' ? 1 : 0)) : null };
  });
  if (blocks.some(block => !block.complete)) return { candidate, baseline, complete: false, plannedTaskBlocks: blocks.length,
    completedTaskBlocks: blocks.filter(block => block.complete).length, difference: null, familySensitivity: null, blocks };
  const families = [...new Set(blocks.map(block => block.family))];
  return { candidate, baseline, complete: true, plannedTaskBlocks: blocks.length, completedTaskBlocks: blocks.length,
    difference: interval(blocks.map(block => block.difference), 'TASK'),
    familySensitivity: interval(families.map(family => mean(blocks.filter(block => block.family === family).map(block => block.difference))), 'FAMILY'),
    blocks, interpretation: 'fixed self-authored task set; repeats are not independent tasks; four-family sensitivity is descriptive' };
}

export function pairedSuccessfulCost(rows, tasks, candidate, baseline) {
  const pairs = [];
  for (const task of tasks) for (let repetition = 1; repetition <= 3; repetition++) {
    const a = rows.find(row => row.taskId === task.id && row.repetition === repetition && row.arm === candidate);
    const b = rows.find(row => row.taskId === task.id && row.repetition === repetition && row.arm === baseline);
    requireThat(a && b, 'paired cost rows missing');
    if (a.status === 'PASS' && b.status === 'PASS' && !a.accountingInvalid && !b.accountingInvalid
        && a.usage?.actualTotalTokens != null && b.usage?.actualTotalTokens != null) {
      pairs.push({ taskId: task.id, repetition, candidateTokens: a.usage.actualTotalTokens, baselineTokens: b.usage.actualTotalTokens,
        tokenDifference: a.usage.actualTotalTokens - b.usage.actualTotalTokens, requestDifference: a.dispatchedProviderCalls - b.dispatchedProviderCalls,
        durationDifferenceMs: a.durationMs - b.durationMs });
    }
  }
  return { candidate, baseline, eligiblePairs: pairs.length, plannedPairs: tasks.length * 3, coverage: pairs.length / (tasks.length * 3),
    tokenDifferenceMean: mean(pairs.map(pair => pair.tokenDifference)), requestDifferenceMean: mean(pairs.map(pair => pair.requestDifference)),
    durationDifferenceMeanMs: mean(pairs.map(pair => pair.durationDifferenceMs)), pairs,
    selection: 'BOTH_SUCCESS_ONLY_SEPARATE_FROM_ALL_ATTEMPTS; no population cost claim or confidence interval' };
}

/** Read-only second implementation. The detached Java audit supplies freshly regraded file/tool outcomes. */
export async function analyzeFrozen(root, javaAuditFile) {
  root = resolve(root);
  requireThat(!resolve(javaAuditFile).startsWith(root + sep), 'audit must be detached from sealed artifacts');
  const stored = await json(resolve(root, 'artifact-hashes.json'));
  requireThat(isDeepStrictEqual(stored, await artifactHashes(root)), 'artifact integrity failed');
  const bytes = await readFile(resolve(root, 'frozen-data.json')), data = JSON.parse(bytes.toString('utf8'));
  const manifest = await json(resolve(root, 'manifest.json'));
  requireThat(manifest.datasetHash === hash(bytes) && manifest.split === 'FROZEN_HOLDOUT' && manifest.suite === data.version, 'dataset or split differs');
  requireThat((await json(resolve(root, 'source-integrity.json'))).unchanged === true, 'source changed during execution');
  for (const [filename, digest] of Object.entries(manifest.sourceHashes)) {
    requireThat(!filename.includes('..') && !filename.includes('\\') && !filename.includes(':') && !filename.startsWith('/'), 'unsafe source path');
    requireThat(hash(await readFile(resolve(root, 'source-inputs', filename))) === digest, 'source snapshot differs');
  }
  requireThat(isDeepStrictEqual(manifest.limits, data.limitsDraft) && isDeepStrictEqual(manifest.settings, data.settingsDraft)
    && isDeepStrictEqual(manifest.model, data.modelDraft), 'runtime settings differ from frozen contract');
  const original = manifest.contextVariants;
  requireThat(original?.baselineManifestHash === '0b408bebc99f4175318d5b70e0d0fb56342e581a8ff4e02a4936f9e47f06fbc5'
    && ['ORIGINAL_CONTEXT_MODULE_ONLY_SHARED_OTHER_RUNTIME','ORIGINAL_CONTEXT_PRIVATE_STRATEGY_SHARED_CORRECTED_COUNTING_AND_OTHER_RUNTIME'].includes(original.scope), 'original context assembly missing');
  requireThat(hash(await readFile(resolve(root, 'baseline/original-context.jar'))) === original.originalJarHash
    && isDeepStrictEqual(await json(resolve(root, 'baseline/assembly.json')), original), 'original binary differs');
  requireThat(hash(await readFile(resolve(root, 'source-inputs/benchmarks/baselines/context-memory-context-original-v1/manifest.json'))) === original.baselineManifestHash, 'original source manifest differs');
  const compile = await json(resolve(root, 'baseline/compile.json'));
  requireThat(compile.compiled === true && compile.sourceManifestHash === original.baselineManifestHash, 'baseline compilation not confirmed');
  requireThat(data.limitsDraft.instanceProviderCalls <= 12 && data.limitsDraft.instanceTotalTokens <= 45000
    && data.limitsDraft.totalProviderCalls === 216 * data.limitsDraft.instanceProviderCalls
    && data.limitsDraft.totalTokens === 216 * data.limitsDraft.instanceTotalTokens && data.limitsDraft.roundDeadlineSeconds === 7200, 'frozen caps differ');
  const planned = frozenOrder(data);
  requireThat(isDeepStrictEqual(planned, await json(resolve(root, 'ordered-instances.json'))), 'frozen order differs');
  const rows = await lines(resolve(root, 'instances.jsonl'));
  const byId = new Map(rows.map(row => [row.id, row]));
  requireThat(byId.size === 216 && rows.length === 216 && planned.every(row => byId.has(row.id)), 'recorded denominator differs');
  const audit = await json(javaAuditFile), auditRows = new Map(audit.recomputed.map(row => [row.id, row]));
  requireThat(audit.artifactIntegrity === true && audit.outcomesAgree === true && audit.planned === 216 && auditRows.size === 216,
    'independent mechanical audit failed');
  const allEntries = [], aliases = new Set(), checks = [], stageEntries = new Map();
  let dispatches = 0;
  for (const instance of planned) {
    const row = byId.get(instance.id), mechanical = auditRows.get(instance.id);
    requireThat(mechanical && ['taskId', 'suite', 'arm'].every(key => row[key] === instance[key]), 'row identity differs');
    row.repetition = instance.repetition;
    requireThat(['NOT_RUN', 'PASS', 'FAIL', 'EVALUATION_FAILURE'].includes(row.status), 'unknown row status');
    const directory = resolve(root, 'instances', instance.id);
    const names = await readdir(resolve(directory, 'calls')).catch(error => { if (error.code === 'ENOENT') return []; throw error; });
    if (row.status === 'NOT_RUN') {
      requireThat(names.length === 0 && row.usage === null && row.outcome === null && row.dispatchedProviderCalls === 0 && mechanical.status === 'NOT_RUN',
        'unstarted instance contains work');
      checks.push({ id: instance.id, attempted: false, actualTotalTokens: null, costKnown: false }); continue;
    }
    requireThat(isDeepStrictEqual(mechanical.outcome, row.outcome) && mechanical.passed === (row.status === 'PASS'), 'mechanical outcome differs');
    const observation = await json(resolve(directory, 'observation.json'));
    const events = await lines(resolve(directory, 'runtime-events.jsonl'));
    const started = events.filter(event => event.eventType === 'provider_call_started');
    const entries = [], bucket = new Map();
    const key = value => JSON.stringify([value.runId, value.turnNumber ?? value.turn, value.payload?.phase ?? value.phase]);
    for (const event of started) bucket.set(key(event), (bucket.get(key(event)) ?? 0) + 1);
    const requests = names.filter(name => name.endsWith('-request.json')).sort();
    const responses = names.filter(name => name.endsWith('-response.json')).sort();
    requireThat(requests.length === responses.length && requests.every(name => responses.includes(name.replace('-request.json', '-response.json'))), 'partial raw transcript');
    for (const name of responses) {
      const raw = await json(resolve(directory, 'calls', name)), entry = raw.usageEntry;
      const request = await json(resolve(directory, 'calls', name.replace('-response.json', '-request.json')));
      requireThat(request.callId === entry.callId && request.phase === entry.phase, 'call attribution differs');
      const parameters = request.parameters;
      requireThat(parameters.temperature === 0 && parameters.maxTokens > 0 && parameters.maxTokens <= 1024
        && parameters.stream === false && parameters.reasoningMode === 'DISABLED'
        && request.tools.every(tool => ['glob', 'grep', 'read', 'write'].includes(tool.name)), 'request policy differs');
      const response = raw.response ?? raw.rejectedResponse;
      if (entry.usage.source === 'ACTUAL') {
        requireThat(response && isDeepStrictEqual(response.usage, entry.usage), 'response usage differs');
        const upper = Buffer.byteLength(JSON.stringify({ messages: request.messages, tools: request.tools }), 'utf8') + data.settingsDraft.framingReserveTokens + 1024;
        requireThat(entry.usage.totalTokens <= upper, 'conservative reservation exceeded');
        const match = key(request); bucket.set(match, (bucket.get(match) ?? 0) - 1);
      }
      const retries = response?.metadata?.retryCount ?? raw.retryCount;
      requireThat(retries == null || retries === 0, 'provider retried');
      if (response?.metadata?.model) aliases.add(response.metadata.model);
      const stage = request.runId === 'session-summary' ? 'INGESTION_SESSION_SUMMARY'
        : request.runId.startsWith('ingest-') ? 'INGESTION_MEMORY_EXTRACT'
        : request.phase === 'COMPACT' ? 'COMPACTION'
        : request.runId === observation.taskRoot ? 'MAIN_TASK'
        : request.phase === 'MEMORY_EXTRACT' ? 'POST_TASK_MEMORY' : 'INDEPENDENT_VERIFICATION';
      if (!stageEntries.has(stage)) stageEntries.set(stage, []);
      stageEntries.get(stage).push(entry);
      entries.push(entry); allEntries.push(entry);
    }
    requireThat(new Set(entries.map(entry => entry.callId)).size === entries.length, 'duplicate usage entries');
    const usage = usageTotals(entries);
    requireThat(isDeepStrictEqual(usage, row.usage) && isDeepStrictEqual(usage, mechanical.usage), 'usage totals differ');
    requireThat(started.length === row.dispatchedProviderCalls && started.length === mechanical.dispatchedProviderCalls
      && started.length <= data.limitsDraft.instanceProviderCalls && (usage.actualTotalTokens ?? 0) <= data.limitsDraft.instanceTotalTokens,
      'dispatch or instance budget differs');
    const costKnown = !row.accountingInvalid && mechanical.accountingUnknown === false && [...bucket.values()].every(value => value === 0);
    checks.push({ id: instance.id, attempted: true, actualTotalTokens: usage.actualTotalTokens, costKnown, dispatches: started.length,
      unavailableEntries: usage.unavailableCalls, estimatedEntries: usage.estimatedCalls });
    dispatches += started.length;
  }
  const usage = usageTotals(allEntries), attempted = checks.filter(check => check.attempted).length;
  requireThat(dispatches <= data.limitsDraft.totalProviderCalls && (usage.actualTotalTokens ?? 0) <= data.limitsDraft.totalTokens, 'round cap differs');
  requireThat(audit.attempted === attempted && audit.notRun === 216 - attempted && audit.passed === rows.filter(row => row.status === 'PASS').length, 'audit denominator differs');
  const groups = [...C, ...M].map(arm => {
    const group = rows.filter(row => row.arm === arm), attemptedRows = group.filter(row => row.status !== 'NOT_RUN');
    const actualRows = attemptedRows.filter(row => row.usage?.actualTotalTokens != null);
    const failures = {};
    for (const row of attemptedRows.filter(row => row.status !== 'PASS')) for (const kind of [row.failureType, ...(row.outcome?.failureTypes ?? [])].filter(Boolean)) failures[kind] = (failures[kind] ?? 0) + 1;
    return { arm, planned: group.length, attempted: attemptedRows.length, notRun: group.length - attemptedRows.length,
      passed: group.filter(row => row.status === 'PASS').length, outputValid: group.filter(row => row.outcome?.outputValid).length,
      allThreeSuccessTasks: data.tasks.filter(task => group.filter(row => row.taskId === task.id && row.status === 'PASS').length === 3).length,
      actualRows: actualRows.length, actualTotalTokens: actualRows.length ? sum(actualRows.map(row => row.usage.actualTotalTokens)) : null,
      dispatchedCalls: sum(attemptedRows.map(row => row.dispatchedProviderCalls)), summedInstanceDurationMs: sum(attemptedRows.map(row => row.durationMs)), failures };
  });
  const comparisons = [];
  for (const [suite, candidate, baseline] of [['COMPRESSION', C[2], C[1]], ['COMPRESSION', C[2], C[0]], ['MEMORY', M[2], M[1]], ['MEMORY', M[2], M[0]]]) {
    const tasks = data.tasks.filter(task => task.suite === suite);
    comparisons.push({ suite, ...pairedCompletion(rows, tasks, candidate, baseline), successfulCost: pairedSuccessfulCost(rows, tasks, candidate, baseline),
      interpretation: baseline === M[0] ? 'NO_HISTORY_LOWER_BOUND; UNKNOWN_TASKS_REPORTED_SEPARATELY' : 'MATCHED_SHARED_RUNTIME' });
  }
  const phases = Object.fromEntries([...new Set(allEntries.map(entry => entry.phase))].sort().map(phase => [phase, usageTotals(allEntries.filter(entry => entry.phase === phase))]));
  const unknownTasks = data.tasks.filter(task => task.gold.unanswerable).map(task => task.id);
  return { version: 'context-memory-frozen-statistics-v1', artifactHash: hash(JSON.stringify(stored)), datasetHash: manifest.datasetHash,
    planned: 216, independentTasks: 24, repetitionsPerTask: 3, attempted, notRun: 216 - attempted, passed: rows.filter(row => row.status === 'PASS').length,
    integrity: true, javaAuditVerified: true, dispatches, usage, phases,
    stages: Object.fromEntries([...stageEntries].map(([stage, entries]) => [stage, usageTotals(entries)])), observedModelAliases: [...aliases].sort(),
    allDispatchedUsageKnown: attempted > 0 && checks.filter(check => check.attempted).every(check => check.costKnown),
    statisticsComplete: attempted === 216 && checks.every(check => check.costKnown), groups, comparisons, checks,
    unknownTasks, unknownOutcomes: rows.filter(row => unknownTasks.includes(row.taskId)).map(row => ({ id: row.id, arm: row.arm, status: row.status, outcome: row.outcome })),
    semanticEvidenceReviewed: false, liveFullGateSatisfied: false, modelEffectClaimsAllowed: false,
    limits: data.limitsDraft, timeInterpretation: 'all instance wall times include ingestion through verification; exclude assembly/preflight; no production latency claim',
    boundary: 'Second implementation checks denominator and raw usage. Java independently regrades file/tool outcomes. Semantic review and live-full remain separate gates.' };
}
