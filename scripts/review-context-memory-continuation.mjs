import { readFile, readdir, lstat } from 'node:fs/promises';
import { resolve, relative, sep } from 'node:path';
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';

const hash = value => createHash('sha256').update(value).digest('hex');
const json = async file => JSON.parse(await readFile(file, 'utf8'));
const digest = value => hash(JSON.stringify(value));
const requireThat = (condition, message) => { if (!condition) throw new Error(message); };

async function hashes(root) {
  const values = {};
  async function walk(dir) {
    for (const name of (await readdir(dir)).sort()) {
      const file = resolve(dir, name);
      const stat = await lstat(file);
      requireThat(!stat.isSymbolicLink(), 'artifact symlink is unsupported');
      if (stat.isDirectory()) await walk(file);
      else if (stat.isFile()) {
        const key = relative(root, file).split(sep).join('/');
        if (key !== 'artifact-hashes.json') values[key] = hash(await readFile(file));
      }
    }
  }
  await walk(root);
  return values;
}

async function files(directory) {
  return (await readdir(directory).catch(error => {
    if (error.code === 'ENOENT') return [];
    throw error;
  })).sort();
}

/** Detached evaluator packet. It never writes to the sealed run or constructs a provider. */
export async function prepareEvidenceReview(output) {
  const root = resolve(output);
  const stored = await json(resolve(root, 'artifact-hashes.json'));
  requireThat(isDeepStrictEqual(stored, await hashes(root)), 'artifact integrity failed');
  const frozenBytes = await readFile(resolve(root, 'frozen-data.json'));
  const data = JSON.parse(frozenBytes.toString('utf8'));
  const manifest = await json(resolve(root, 'manifest.json'));
  requireThat(manifest.datasetHash === hash(frozenBytes), 'dataset hash differs');
  requireThat((await json(resolve(root, 'source-integrity.json'))).unchanged === true, 'source changed during run');
  requireThat(['DEVELOPMENT_ONLY', 'FROZEN_HOLDOUT'].includes(data.split), 'recognized continuation split required');
  const planned = await json(resolve(root, 'ordered-instances.json'));
  const rows = (await readFile(resolve(root, 'instances.jsonl'), 'utf8')).split(/\r?\n/)
    .filter(line => line.trim()).map(line => JSON.parse(line));
  const byId = new Map(rows.map(row => [row.id, row]));
  requireThat(byId.size === rows.length && rows.length === planned.length
    && planned.every(instance => byId.has(instance.id)), 'planned denominator differs');
  const packets = [];
  for (const instance of planned) {
    const row = byId.get(instance.id);
    if (row.status === 'NOT_RUN' || instance.suite !== 'MEMORY') continue;
    const task = data.tasks.find(value => value.id === instance.taskId);
    requireThat(task, 'task is missing');
    const original = [...task.agentInput.histories];
    const freshSourceIds = Object.keys(task.gold.workspaceEvidenceSources ?? {});
    for (const [sourceId, filename] of Object.entries(task.gold.workspaceEvidenceSources ?? {})) {
      requireThat(task.gold.requiredReadTargets?.includes(filename), 'fresh source requires a mandatory read');
      const content = task.agentInput.workspaceFiles?.[filename];
      requireThat(typeof content === 'string', 'fresh source input absent');
      original.push({ id: sourceId, revision: 1, sourceKind: 'FRESH_WORKSPACE_TOOL', filename,
        messages: [{ role: 'tool', content }] });
    }
    const caseId = hash(manifest.datasetHash + instance.id).slice(0, 20);
    const directory = resolve(root, 'instances', instance.id);
    const materials = [];
    const add = (stage, kind, text, sourceIds = []) => {
      if (typeof text !== 'string' || !text.trim()) return;
      materials.push({ id: 'e' + materials.length, stage, kind, text,
        contentHash: hash(text), sourceIds });
    };
    const parents = new Map();
    let previous = {};
    // Snapshot differences describe derivation only. A reviewer must check each actual assertion.
    for (const source of task.agentInput.histories) {
      const revisionFile = resolve(directory, 'ingestion', source.id + '-memory-revision.json');
      const snapshot = await json(revisionFile).catch(error => {
        if (error.code === 'ENOENT') return null;
        throw error;
      });
      if (snapshot) {
        for (const [filename, entry] of Object.entries(snapshot)) {
          if (!isDeepStrictEqual(entry, previous[filename])) parents.set(filename, [source.id]);
        }
        previous = snapshot;
      }
      const result = await json(resolve(directory, 'ingestion', source.id + '-result.json')).catch(error => {
        if (error.code === 'ENOENT') return null;
        throw error;
      });
      if (result) add('EXTRACTION', 'SESSION_SUMMARY', result.session?.summary, [source.id]);
    }
    for (const [filename, entry] of Object.entries(previous)) {
      add('EXTRACTION', 'MEMORY_ENTRY', entry.content, parents.get(filename) ?? []);
    }
    const observation = await json(resolve(directory, 'observation.json'));
    const capturedEvents = (await readFile(resolve(directory, 'runtime-events.jsonl'), 'utf8')).split(/\r?\n/)
      .filter(line => line.trim()).map(line => JSON.parse(line));
    const taskRoot = capturedEvents.find(event => event.eventType === 'run_started' && event.parentRunId === null)?.runId ?? null;
    requireThat(taskRoot === observation.taskRoot, 'task root differs from captured start event');
    for (const name of await files(resolve(directory, 'calls'))) {
      if (!name.endsWith('-request.json')) continue;
      const request = await json(resolve(directory, 'calls', name));
      if (taskRoot === null || request.runId !== taskRoot) continue;
      const response = await json(resolve(directory, 'calls', name.replace('-request.json', '-response.json')));
      // A budget rejection is also archived as a request, but was never received by the model.
      if (response.usageEntry?.usage?.source !== 'ACTUAL') continue;
      for (const message of request.messages) {
        // These are exact model-visible messages, including later actual archive-read results.
        // Source IDs are intentionally not guessed from marker text or a matching keyword.
        add('RETRIEVAL', 'MODEL_INPUT_' + message.role.toUpperCase(), message.content);
      }
    }
    packets.push({ caseId, query: task.agentInput.query,
      originalSources: original, materials,
      extractionApplicable: ['M2_CURRENT_MEMORY_AND_SESSION_BM25', 'M2_NATIVE_MEMORY_SESSION'].includes(instance.arm),
      factKinds: Object.fromEntries(Object.keys(task.gold.requiredFields).map(field => [field,
        (task.gold.factSources?.[field] ?? []).flat().some(id => freshSourceIds.includes(id)) ? 'FRESH_TOOL' : 'HISTORY'])),
      unanswerable: task.gold.unanswerable === true,
      expectedFields: task.gold.requiredFields,
      factSources: task.gold.factSources ?? {},
      evidenceAlternatives: task.gold.evidenceAlternatives ?? [] });
  }
  const body = { version: 'context-memory-evidence-review-v3', datasetHash: manifest.datasetHash,
    artifactHash: digest(stored), planned: planned.length, reviewedCandidates: packets.length,
    scope: 'MEMORY_' + data.split, packets,
    trustBoundary: 'Reviewer judges semantic support. Exact quotes and hashes only validate attribution; they do not prove entailment.' };
  return { ...body, packetHash: digest(body) };
}

function checkEvidence(packet, stage, evidence, field, supported) {
  requireThat(evidence && typeof evidence.rationale === 'string' && evidence.rationale.trim(), 'review rationale required');
  const source = packet.originalSources.find(value => value.id === evidence.sourceId);
  requireThat(source, 'unknown original source');
  requireThat(typeof evidence.originalQuote === 'string' && evidence.originalQuote.trim()
    && source.messages.some(message => message.content?.includes(evidence.originalQuote)), 'original quote does not exist');
  const material = packet.materials.find(value => value.id === evidence.materialId);
  requireThat(material?.stage === stage, 'evidence stage differs');
  requireThat(typeof evidence.materialQuote === 'string' && evidence.materialQuote.trim()
    && material.text.includes(evidence.materialQuote), 'material quote does not exist');
  if (stage === 'EXTRACTION') requireThat(material.sourceIds.includes(source.id), 'extraction derivation differs');
  if (supported) {
    requireThat(source.messages.some(message => ['user', 'tool'].includes(message.role)
      && message.content?.includes(evidence.originalQuote)), 'assistant hypothesis is not authoritative evidence');
    const alternatives = packet.factSources[field];
    requireThat(Array.isArray(alternatives) && alternatives.length > 0, 'per-field source rubric required');
    requireThat(alternatives.flat().includes(source.id), 'source is outside the required fact rubric');
  }
  return source.id;
}

/** Validates complete independent annotations. No labels are generated from a runner's PASS or gold text. */
export function auditEvidenceReview(packet, annotation) {
  const { packetHash, ...body } = packet;
  requireThat(digest(body) === packetHash && annotation.packetHash === packetHash, 'review packet hash differs');
  requireThat(annotation.reviewer?.name?.trim() && annotation.reviewer?.method?.trim(), 'identified reviewer and method required');
  requireThat(Array.isArray(annotation.cases), 'review cases required');
  const byId = new Map(annotation.cases.map(value => [value.caseId, value]));
  requireThat(byId.size === annotation.cases.length && byId.size === packet.packets.length
    && packet.packets.every(value => byId.has(value.caseId)), 'review coverage is incomplete');
  let extractionTotal = 0, extractionSupported = 0, retrievalTotal = 0, retrievalSupported = 0;
  let historicalRetrievalTotal = 0, historicalRetrievalSupported = 0, freshRetrievalTotal = 0, freshRetrievalSupported = 0;
  const results = [];
  for (const candidate of packet.packets) {
    const review = byId.get(candidate.caseId);
    const fields = Object.keys(candidate.expectedFields);
    requireThat(review.facts && isDeepStrictEqual(Object.keys(review.facts).sort(), fields.sort()), 'fact coverage is incomplete');
    const supportedSources = new Set();
    const factResults = {};
    for (const field of fields) {
      const verdicts = {};
      for (const stage of ['EXTRACTION', 'RETRIEVAL']) {
        const item = review.facts[field][stage.toLowerCase()];
        requireThat(item && Array.isArray(item.evidence), 'stage review required');
        if (stage === 'EXTRACTION' && (!candidate.extractionApplicable || candidate.factKinds?.[field] === 'FRESH_TOOL')) {
          requireThat(item.verdict === 'NOT_APPLICABLE' && item.evidence.length === 0, 'extraction is not applicable');
        } else if (candidate.unanswerable) {
          requireThat(item.verdict === 'UNANSWERABLE' && item.evidence.length === 0, 'unknown query cannot have a fact Recall score');
        } else {
          requireThat(['SUPPORTED', 'CONTRADICTED', 'MISSING'].includes(item.verdict), 'unknown verdict');
          const supported = item.verdict === 'SUPPORTED';
          requireThat(item.verdict === 'MISSING' ? item.evidence.length === 0 : item.evidence.length > 0,
            'evidence required for semantic verdict');
          const ids = new Set(item.evidence.map(evidence => checkEvidence(candidate, stage, evidence, field, supported)));
          if (supported) requireThat(candidate.factSources[field].some(required => required.every(id => ids.has(id))),
            'complete source set is missing');
          if (stage === 'EXTRACTION') { extractionTotal++; if (supported) extractionSupported++; }
          else {
            retrievalTotal++; if (supported) { retrievalSupported++; ids.forEach(id => supportedSources.add(id)); }
            if (candidate.factKinds?.[field] === 'FRESH_TOOL') { freshRetrievalTotal++; if (supported) freshRetrievalSupported++; }
            else { historicalRetrievalTotal++; if (supported) historicalRetrievalSupported++; }
          }
        }
        verdicts[stage.toLowerCase()] = item.verdict;
      }
      factResults[field] = verdicts;
    }
    results.push({ caseId: candidate.caseId, facts: factResults,
      reviewerSupportedSources: [...supportedSources],
      allRequiredEvidenceSupported: candidate.unanswerable ? null
        : candidate.evidenceAlternatives.some(required => required.every(id => supportedSources.has(id))) });
  }
  return { packetHash, reviewer: annotation.reviewer, reviewedCases: results.length,
    extraction: { facts: extractionTotal, supported: extractionSupported, coverage: extractionTotal ? extractionSupported / extractionTotal : null },
    retrieval: { facts: retrievalTotal, supported: retrievalSupported, coverage: retrievalTotal ? retrievalSupported / retrievalTotal : null },
    historicalRetrieval: { facts: historicalRetrievalTotal, supported: historicalRetrievalSupported },
    freshToolEvidence: { facts: freshRetrievalTotal, supported: freshRetrievalSupported },
    results, semanticJudge: 'ANNOTATED_REVIEW_NOT_AUTOMATIC_ENTAILMENT',
    sourceRecallAtK: null, precisionAtK: null, modelEffectClaimAllowed: false };
}
