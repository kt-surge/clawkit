import { mkdir, writeFile, readFile, readdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { prepareEvidenceReview as prepare, auditEvidenceReview as audit } from './review-context-memory-continuation.mjs';

const hash = value => createHash('sha256').update(value).digest('hex');
const write = async (root, name, value) => {
  const path = resolve(root, name);
  await mkdir(resolve(path, '..'), { recursive: true });
  await writeFile(path, typeof value === 'string' ? value : JSON.stringify(value), { flag: 'wx' });
};

/** Offline contracts, with deliberately false source attribution and model hypotheses. No provider. */
export async function runReviewContracts(root, review = { prepareEvidenceReview: prepare, auditEvidenceReview: audit }) {
  const { prepareEvidenceReview, auditEvidenceReview } = review;
  await mkdir(root); // Refuse to reuse previous evidence.
  const sources = [
    { id: 'old-timeout', messages: [{ role: 'user', content: 'timeoutSeconds=5。' }] },
    { id: 'new-timeout', messages: [{ role: 'user', content: 'timeoutSeconds=8。' },
      { role: 'assistant', content: '猜测可以设为43，但未确认。' }] }
  ];
  const unknownSources = [{ id: 'garden', messages: [{ role: 'user', content: '花园每天浇水。' },
    { role: 'assistant', content: '猜测订单服务部署在杭州，未确认。' }] }];
  const healthHistory = [{ id: 'health-old', messages: [{ role: 'user', content: '此前观测 previousEpoch=4，healthy=true。' }] }];
  const data = { split: 'DEVELOPMENT_ONLY', tasks: [
    { id: 'updated', agentInput: { query: '恢复最后确认的超时。', histories: sources },
      gold: { requiredFields: { timeoutSeconds: 8 }, factSources: { timeoutSeconds: [['new-timeout']] }, evidenceAlternatives: [['new-timeout']] } },
    { id: 'unknown', agentInput: { query: '确认部署地区。', histories: unknownSources },
      gold: { requiredFields: { deploymentRegion: null }, unanswerable: true, factSources: {}, evidenceAlternatives: [] } },
    { id: 'health', agentInput: { query: '恢复旧版本记录并读取当前健康。', histories: healthHistory,
        workspaceFiles: { 'probe.json': '{"healthy":false}' } },
      gold: { requiredFields: { previousEpoch: 4, healthy: false }, requiredReadTargets: ['probe.json'],
        workspaceEvidenceSources: { 'health-current': 'probe.json' },
        factSources: { previousEpoch: [['health-old']], healthy: [['health-current']] },
        evidenceAlternatives: [['health-old', 'health-current']] } }
  ] };
  const plan = [
    { id: 'updated-m2', taskId: 'updated', suite: 'MEMORY', arm: 'M2_CURRENT_MEMORY_AND_SESSION_BM25' },
    { id: 'unknown-m0', taskId: 'unknown', suite: 'MEMORY', arm: 'M0_NO_HISTORY' },
    { id: 'updated-m1-not-run', taskId: 'updated', suite: 'MEMORY', arm: 'M1_FULL_ARCHIVE_READ_GREP' },
    { id: 'health-m2', taskId: 'health', suite: 'MEMORY', arm: 'M2_NATIVE_MEMORY_SESSION' }
  ];
  await write(root, 'frozen-data.json', data);
  await write(root, 'manifest.json', { datasetHash: hash(await readFile(resolve(root, 'frozen-data.json'))) });
  await write(root, 'source-integrity.json', { unchanged: true });
  await write(root, 'ordered-instances.json', plan);
  await write(root, 'instances.jsonl', plan.map((value, index) => JSON.stringify({ id: value.id, status: index === 2 ? 'NOT_RUN' : 'PASS' })).join('\n'));
  await write(root, 'instances/updated-m2/ingestion/old-timeout-memory-revision.json', { 'timeout.md': { content: 'timeoutSeconds=5。' } });
  await write(root, 'instances/updated-m2/ingestion/new-timeout-memory-revision.json', { 'timeout.md': { content: 'timeoutSeconds=8。' } });
  await write(root, 'instances/updated-m2/ingestion/old-timeout-result.json', { session: { summary: 'timeoutSeconds=5。' } });
  await write(root, 'instances/updated-m2/ingestion/new-timeout-result.json', { session: { summary: 'timeoutSeconds=8。' } });
  await write(root, 'instances/health-m2/ingestion/health-old-result.json', { session: { summary: 'previousEpoch=4，healthy=true。' } });
  for (const id of ['updated-m2', 'unknown-m0', 'health-m2']) {
    await write(root, `instances/${id}/observation.json`, { taskRoot: id + '-root' });
    await write(root, `instances/${id}/runtime-events.jsonl`, JSON.stringify({ eventType: 'run_started', runId: id + '-root', parentRunId: null }));
    await write(root, `instances/${id}/calls/1-request.json`, { runId: id + '-root', messages: [
      { role: 'system', content: id === 'updated-m2' ? '[Relevant Memory: timeout]\ntimeoutSeconds=8。' : id === 'health-m2' ? 'previousEpoch=4，healthy=true。' : '信息不足时保持未知。' }
    ] });
    await write(root, `instances/${id}/calls/2-request.json`, { runId: id + '-verification', messages: [
      { role: 'system', content: 'VERIFIER_ONLY_DO_NOT_COUNT_AS_RECALL' }
    ] });
    if (id === 'health-m2') {
      await write(root, `instances/${id}/calls/4-request.json`, { runId: id + '-root', messages: [{ role: 'tool', content: '{"healthy":false}' }] });
      await write(root, `instances/${id}/calls/4-response.json`, { usageEntry: { usage: { source: 'ACTUAL' } } });
    }
    for (const call of [1, 2]) await write(root, `instances/${id}/calls/${call}-response.json`, { usageEntry: { usage: { source: 'ACTUAL' } } });
    await write(root, `instances/${id}/calls/3-request.json`, { runId: id + '-root', messages: [
      { role: 'system', content: 'NOT_SENT_BUDGET_REJECTION_DO_NOT_COUNT_AS_RECALL' }
    ] });
    await write(root, `instances/${id}/calls/3-response.json`, { usageEntry: { usage: { source: 'UNAVAILABLE' } } });
  }
  const stored = {};
  async function seal(dir, prefix = '') {
    for (const name of await readdir(dir, { withFileTypes: true })) {
      const key = prefix + name.name;
      if (name.isDirectory()) await seal(resolve(dir, name.name), key + '/');
      else stored[key] = hash(await readFile(resolve(dir, name.name)));
    }
  }
  await seal(root); await write(root, 'artifact-hashes.json', stored);
  const packet = await prepareEvidenceReview(root);
  assert.equal(packet.planned, 4); assert.equal(packet.packets.length, 3);
  assert.ok(packet.packets.every(value => value.materials.every(material => !material.text.includes('VERIFIER_ONLY'))));
  assert.ok(packet.packets.every(value => value.materials.every(material => !material.text.includes('NOT_SENT_BUDGET_REJECTION'))));
  const known = packet.packets.find(value => value.expectedFields.timeoutSeconds === 8);
  const health = packet.packets.find(value => 'healthy' in value.expectedFields);
  assert.deepEqual(health.factKinds, { previousEpoch: 'HISTORY', healthy: 'FRESH_TOOL' });
  assert.ok(health.materials.filter(value => value.stage === 'EXTRACTION').every(value => !value.sourceIds.includes('health-current')));
  const oldHealthMaterial = health.materials.find(value => value.stage === 'EXTRACTION');
  const oldHealthRetrieval = health.materials.find(value => value.stage === 'RETRIEVAL' && value.text.includes('previousEpoch=4'));
  const freshHealthMaterial = health.materials.find(value => value.stage === 'RETRIEVAL' && value.text.includes('"healthy":false'));
  const healthEvidence = (material, fresh) => ({ sourceId: fresh ? 'health-current' : 'health-old',
    originalQuote: fresh ? '"healthy":false' : 'previousEpoch=4', materialId: material.id,
    materialQuote: fresh ? '"healthy":false' : 'previousEpoch=4', rationale: '历史事实与当前工具观测分别归因。' });
  const unknown = packet.packets.find(value => value.unanswerable);
  const extracted = known.materials.find(value => value.kind === 'MEMORY_ENTRY');
  assert.deepEqual(extracted.sourceIds, ['new-timeout']);
  const retrieved = known.materials.find(value => value.stage === 'RETRIEVAL');
  const evidence = material => ({ sourceId: 'new-timeout', originalQuote: 'timeoutSeconds=8',
    materialId: material.id, materialQuote: 'timeoutSeconds=8', rationale: '最新用户确认值在该材料中保留。' });
  const annotation = { packetHash: packet.packetHash, reviewer: { name: 'fixture-reviewer', method: 'FIXTURE_ONLY_NOT_MODEL_QUALITY' }, cases: [
    { caseId: known.caseId, facts: { timeoutSeconds: {
      extraction: { verdict: 'SUPPORTED', evidence: [evidence(extracted)] },
      retrieval: { verdict: 'SUPPORTED', evidence: [evidence(retrieved)] }
    } } },
    { caseId: unknown.caseId, facts: { deploymentRegion: {
      extraction: { verdict: 'NOT_APPLICABLE', evidence: [] }, retrieval: { verdict: 'UNANSWERABLE', evidence: [] }
    } } },
    { caseId: health.caseId, facts: {
      previousEpoch: { extraction: { verdict: 'SUPPORTED', evidence: [healthEvidence(oldHealthMaterial, false)] },
        retrieval: { verdict: 'SUPPORTED', evidence: [healthEvidence(oldHealthRetrieval, false)] } },
      healthy: { extraction: { verdict: 'NOT_APPLICABLE', evidence: [] },
        retrieval: { verdict: 'SUPPORTED', evidence: [healthEvidence(freshHealthMaterial, true)] } }
    } }
  ] };
  const report = auditEvidenceReview(packet, annotation);
  assert.equal(report.extraction.facts, 2); assert.equal(report.retrieval.facts, 3);
  assert.deepEqual(report.historicalRetrieval, { facts: 2, supported: 2 });
  assert.deepEqual(report.freshToolEvidence, { facts: 1, supported: 1 });
  assert.throws(() => auditEvidenceReview(packet, mutateHealth(annotation, value => { value.cases[2].facts.healthy.extraction = { verdict: 'SUPPORTED', evidence: [healthEvidence(oldHealthMaterial, false)] }; })), /not applicable/);
  assert.throws(() => auditEvidenceReview(packet, mutateHealth(annotation, value => { value.cases[2].facts.healthy.retrieval.evidence = [{ sourceId: 'health-old', originalQuote: 'healthy=true', materialId: oldHealthRetrieval.id, materialQuote: 'healthy=true', rationale: '故意把旧观测当成当前状态。' }]; })), /rubric/);
  assert.equal(report.results.find(value => value.caseId === unknown.caseId).allRequiredEvidenceSupported, null);
  assert.equal(report.sourceRecallAtK, null); assert.equal(report.modelEffectClaimAllowed, false);
  const mutate = change => { const value = structuredClone(annotation); change(value); return value; };
  assert.throws(() => auditEvidenceReview(packet, mutate(value => { value.cases[0].facts.timeoutSeconds.extraction.evidence[0].sourceId = 'old-timeout'; })), /quote|derivation|rubric/);
  assert.throws(() => auditEvidenceReview(packet, mutate(value => { value.cases[0].facts.timeoutSeconds.retrieval.evidence[0].originalQuote = '猜测可以设为43'; })), /hypothesis/);
  assert.throws(() => auditEvidenceReview(packet, mutate(value => { value.cases[0].facts.timeoutSeconds.retrieval.evidence[0].materialId = extracted.id; })), /stage/);
  assert.throws(() => auditEvidenceReview(packet, mutate(value => { value.cases[0].facts.timeoutSeconds.retrieval.evidence[0].materialQuote = 'timeoutSeconds=99'; })), /quote/);
  assert.throws(() => auditEvidenceReview(packet, mutate(value => { value.cases.pop(); })), /incomplete/);
  assert.throws(() => auditEvidenceReview(packet, mutate(value => { value.cases[1].facts.deploymentRegion.retrieval.verdict = 'SUPPORTED'; })), /unknown query/);
  const changedPacket = structuredClone(packet); changedPacket.packets[0].expectedFields.timeoutSeconds = 99;
  assert.throws(() => auditEvidenceReview(changedPacket, annotation), /hash/);
  await writeFile(resolve(root, 'instances/updated-m2/calls/1-request.json'), '{"tampered":true}');
  await assert.rejects(() => prepareEvidenceReview(root), /integrity/);
  return { fixtureOnly: true, contracts: 12, passed: 12, networkCalls: 0,
    coverageClaims: 'ANNOTATION_CONTRACTS_ONLY_NO_MEMORY_EFFECT_CLAIM' };
}

function mutateHealth(annotation, change) { const value = structuredClone(annotation); change(value); return value; }
