import assert from 'node:assert/strict';
import { readFile, writeFile, cp, readdir, lstat } from 'node:fs/promises';
import { resolve, relative, sep } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { frozenOrder, usageTotals, pairedCompletion, pairedSuccessfulCost, analyzeFrozen } from './analyze-context-memory-frozen.mjs';

/** Synthetic numerical/denominator contracts; never a memory effectiveness result. */
export async function runFrozenStatisticsContracts(datasetFile, preparationRoot, preparationAudit) {
  const data = JSON.parse(await readFile(datasetFile, 'utf8'));
  const planned = frozenOrder(data);
  assert.equal(planned.length, 216);
  assert.equal(new Set(planned.map(row => row.id)).size, 216);
  for (const task of data.tasks) assert.equal(planned.filter(row => row.taskId === task.id).length, 9);
  const bad = structuredClone(data); bad.tasks[1].id = bad.tasks[0].id;
  assert.throws(() => frozenOrder(bad), /unique/);
  const unavailable = { callId: 'unknown', phase: 'REACT', durationMs: 1, failureType: 'Timeout',
    usage: { source: 'UNAVAILABLE', promptTokens: 0, completionTokens: 0, totalTokens: 0,
      promptCacheHitTokens: 0, promptCacheMissTokens: 0, reasoningTokens: 0 } };
  assert.equal(usageTotals([unavailable]).actualTotalTokens, null);
  assert.equal(usageTotals([]).completeActualUsage, false);
  const actual = { ...unavailable, callId: 'received', failureType: null, usage: { ...unavailable.usage,
    source: 'ACTUAL', promptTokens: 100, completionTokens: 20, totalTokens: 120, promptCacheHitTokens: 60, promptCacheMissTokens: 40 } };
  const total = usageTotals([unavailable, actual]);
  assert.equal(total.actualTotalTokens, 120); assert.equal(total.actualCalls, 1); assert.equal(total.completeActualUsage, false);
  const wrong = structuredClone(actual); wrong.usage.totalTokens = 119;
  assert.throws(() => usageTotals([wrong]), /add up/);
  const tasks = Array.from({ length: 12 }, (_, i) => ({ id: 'sample-' + i, family: 'family-' + Math.floor(i / 3) }));
  const rows = tasks.flatMap((task, index) => [1, 2, 3].flatMap(repetition => ['candidate', 'baseline'].map(arm => ({
    taskId: task.id, repetition, arm, status: index < (arm === 'candidate' ? 9 : 6) ? 'PASS' : 'FAIL',
    accountingInvalid: false, usage: { actualTotalTokens: arm === 'candidate' ? 100 : 150 },
    dispatchedProviderCalls: arm === 'candidate' ? 2 : 3, durationMs: arm === 'candidate' ? 200 : 300
  }))));
  const comparison = pairedCompletion(rows, tasks, 'candidate', 'baseline');
  assert.equal(comparison.difference.estimate, .25); assert.equal(comparison.difference.units, 12);
  assert.equal(comparison.familySensitivity.units, 4); assert.equal(comparison.difference.lower95, 0);
  assert.equal(comparison.difference.upper95, .5);
  assert.deepEqual(comparison, pairedCompletion([...rows].reverse(), tasks, 'candidate', 'baseline'));
  const cost = pairedSuccessfulCost(rows, tasks, 'candidate', 'baseline');
  assert.equal(cost.eligiblePairs, 18); assert.equal(cost.plannedPairs, 36); assert.equal(cost.coverage, .5);
  assert.equal(cost.tokenDifferenceMean, -50); assert.equal(cost.requestDifferenceMean, -1);
  const missing = structuredClone(rows); missing[0].status = 'NOT_RUN';
  const incomplete = pairedCompletion(missing, tasks, 'candidate', 'baseline');
  assert.equal(incomplete.complete, false); assert.equal(incomplete.difference, null);
  assert.throws(() => pairedCompletion(rows.slice(1), tasks, 'candidate', 'baseline'), /paired repetitions/);
  const report = await analyzeFrozen(preparationRoot, preparationAudit);
  assert.equal(report.attempted, 0); assert.equal(report.notRun, 216); assert.equal(report.usage.actualTotalTokens, null);
  assert.equal(report.statisticsComplete, false); assert.equal(report.modelEffectClaimsAllowed, false);
  assert.ok(report.comparisons.every(value => value.difference === null));
  await rawTranscriptContract(preparationRoot, preparationAudit);
  return { fixtureOnly: true, numericalContracts: 10, passed: 10, networkCalls: 0,
    preparationIntegrity: report.integrity, denominator: report.planned,
    effectClaim: 'NO_MODEL_EFFECT_TESTED' };
}


// Only this copied directory is mutated. The mock mechanical audit exercises transport/usage checks;
// it is not evidence that Java regraded a real model task.
async function rawTranscriptContract(preparationRoot, preparationAudit) {
  const root = resolve(preparationRoot, '..', 'cm-statistics-fixture-' + randomUUID());
  await cp(preparationRoot, root, { recursive: true, errorOnExist: true, force: false });
  const manifest = JSON.parse(await readFile(resolve(root, 'manifest.json'), 'utf8'));
  manifest.mode = 'live-continuation'; manifest.fixtureOnly = true;
  await writeFile(resolve(root, 'manifest.json'), JSON.stringify(manifest));
  const plan = JSON.parse(await readFile(resolve(root, 'ordered-instances.json'), 'utf8'));
  const rows = (await readFile(resolve(root, 'instances.jsonl'), 'utf8')).trim().split(/\r?\n/).map(line => JSON.parse(line));
  const audit = JSON.parse(await readFile(preparationAudit, 'utf8'));
  const row = rows.find(value => value.id === plan[0].id), id = row.id;
  const outcome = { taskCompleted: true, outputValid: true, constraintsObeyed: true, requiredReadsPresent: true, forbiddenWriteAttempts: 0, failureTypes: [] };
  const usage = { source: 'ACTUAL', promptTokens: 100, completionTokens: 20, totalTokens: 120,
    promptCacheHitTokens: 60, promptCacheMissTokens: 40, reasoningTokens: 0 };
  const entry = { callId: id + '-call-1', phase: 'REACT', durationMs: 20, usage, failureType: null };
  Object.assign(row, { status: 'PASS', failureType: null, outcome, usage: usageTotals([entry]), durationMs: 42, dispatchedProviderCalls: 1, accountingInvalid: false });
  const mechanical = audit.recomputed.find(value => value.id === id);
  Object.assign(mechanical, { passed: true, outcome, usage: row.usage, dispatchedProviderCalls: 1, accountingUnknown: false });
  audit.attempted = 1; audit.notRun = 215; audit.passed = 1;
  const directory = resolve(root, 'instances', id);
  const { mkdir } = await import('node:fs/promises'); await mkdir(resolve(directory, 'calls'), { recursive: true });
  const request = { callId: entry.callId, phase: 'REACT', runId: 'fixture-main', turn: 1,
    parameters: { temperature: 0, maxTokens: 1024, stream: false, reasoningMode: 'DISABLED' }, tools: [], messages: [] };
  const response = { usageEntry: entry, response: { usage, metadata: { retryCount: 0, model: 'fixture-only-never-called' } } };
  const requestFile = resolve(directory, 'calls', entry.callId + '-request.json'), responseFile = resolve(directory, 'calls', entry.callId + '-response.json');
  await writeFile(requestFile, JSON.stringify(request)); await writeFile(responseFile, JSON.stringify(response));
  await writeFile(resolve(directory, 'runtime-events.jsonl'), JSON.stringify({ eventType: 'provider_call_started', runId: 'fixture-main', turnNumber: 1, payload: { phase: 'REACT' } }));
  await writeFile(resolve(directory, 'observation.json'), JSON.stringify({ taskRoot: 'fixture-main' }));
  await writeFile(resolve(root, 'instances.jsonl'), rows.map(value => JSON.stringify(value)).join('\n'));
  const auditFile = root + '-mock-audit.json'; await writeFile(auditFile, JSON.stringify(audit), { flag: 'wx' });
  async function seal() {
    const hashes = {};
    async function walk(directory) {
      for (const name of await readdir(directory)) {
        const file = resolve(directory, name), stat = await lstat(file);
        if (stat.isDirectory()) await walk(file);
        else if (stat.isFile()) { const key = relative(root, file).split(sep).join('/'); if (key !== 'artifact-hashes.json') hashes[key] = createHash('sha256').update(await readFile(file)).digest('hex'); }
      }
    }
    await walk(root); await writeFile(resolve(root, 'artifact-hashes.json'), JSON.stringify(hashes));
  }
  await seal();
  const report = await analyzeFrozen(root, auditFile);
  assert.equal(report.attempted, 1); assert.equal(report.notRun, 215); assert.equal(report.dispatches, 1);
  assert.equal(report.usage.actualTotalTokens, 120); assert.equal(report.stages.MAIN_TASK.actualTotalTokens, 120);
  assert.equal(report.statisticsComplete, false);
  response.usageEntry = { ...entry, usage: { ...usage, totalTokens: 121 } };
  await writeFile(responseFile, JSON.stringify(response));
  await assert.rejects(() => analyzeFrozen(root, auditFile), /integrity/);
  await seal();
  await assert.rejects(() => analyzeFrozen(root, auditFile), /response usage differs/);
}
