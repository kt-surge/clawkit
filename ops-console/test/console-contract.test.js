'use strict';

const assert = require('assert/strict');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const consoleRoot = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(consoleRoot, 'dist', 'index.html'), 'utf8');
const scriptMatch = html.match(/<script>([\s\S]*?)<\/script>/);
assert.ok(scriptMatch, 'Console script is missing');
assert.doesNotMatch(scriptMatch[1], /fetch\(|XMLHttpRequest|WebSocket|https?:\/\//,
    'Console must remain local-only');
new Function(scriptMatch[1]);
assert.match(html, /A3 · Shadow/);
assert.match(html, /仅记录反事实决策/);
assert.match(html, /A4 · Auto/);
assert.match(html, /No-Go，未开放/);
assert.match(html, /加载合成导览/);
assert.match(html, /合成导览，非运行证据/);
assert.match(html, /A1 证据裁决轨迹/);
assert.match(html, /investigation-result\.json/);
const consoleButtons = [...html.matchAll(/<button\b[^>]*>([^<]*)<\/button>/g)]
    .map(match => match[1].trim());
assert.deepEqual(consoleButtons, ['加载合成导览'],
    'Console must not grow approval, repair, replay, or policy-edit controls');
assert.doesNotMatch(html, /<form\b/i,
    'Console must not contain a form that could submit an action');

const sha256 = text => crypto.createHash('sha256').update(text, 'utf8').digest('hex');
const runId = 'fixture-observe-a89c8874-29ad-4d0a-bdf8-3e7f4b2a32c7';

const statePayload = JSON.stringify({
    schema: 'automation-state', version: 1, globalPaused: false,
    budgetPolicy: { windowMs: 3600000, policyVersion: 1, maxDiscoveryRuns: 100, maxProviderCalls: 0 },
    targets: { 'fixture-app-down': { paused: false, requested: 1, started: 1, completed: 1,
        skipped: 0, merged: 0, failed: 0, updatedAt: '2026-09-19T08:00:00Z',
        budgetWindowEpoch: 1789804800000, discoveryConsumed: 1, providerConsumed: 0 } }
});
const state = `${statePayload}\n# SHA-256: ${sha256(statePayload)}\n`;

const registryLines = [
    JSON.stringify({ schema: 'incident-registry', version: 1 }),
    JSON.stringify({ fingerprint: 'a'.repeat(64), incidentId: 'inc-fixture-app-down-a89c8874',
        status: 'ACTIVE', firstObservedAt: '2026-09-19T08:00:00Z',
        lastObservedAt: '2026-09-19T08:00:00Z', observationCount: 1, lastRunId: runId,
        lastEvidenceRefs: [`fixture://${runId}/service-status`] })
];
const registryPayload = registryLines.map(line => `${line}\n`).join('');
const registry = `${registryPayload}# SHA-256: ${sha256(registryPayload)}\n`;

const timeline = `${JSON.stringify({ schemaVersion: '1', at: '2026-09-19T08:00:00Z',
    type: 'OBSERVATION_INCIDENT_CREATED', runEventReference: `run://${runId}`,
    fields: { targetId: 'fixture-app-down', evidenceRefs: [`fixture://${runId}/service-status`],
        evidenceFacts: [{ reference: `fixture://${runId}/service-status`, kind: 'SERVICE_STATE', value: 'stopped' }],
        diagnosisEnabled: false, providerCalled: false } })}\n`;

const evidence = {
    'automation-state.json': state,
    'incident-registry.jsonl': registry,
    'observation-timeline.jsonl': timeline
};
const manifestPayload = JSON.stringify({
    schema: 'fixture-evidence-snapshot', version: 1,
    snapshotId: 'fixture-snapshot-b90d9985-3abe-4e1b-9c07-4f805c3b43d8',
    createdAt: '2026-09-19T08:00:01Z', targetId: 'fixture-app-down', timelineEvents: 1,
    files: Object.fromEntries(Object.entries(evidence).map(([name, text]) =>
        [name, { bytes: Buffer.byteLength(text), sha256: sha256(text) }]))
});
const manifest = `${manifestPayload}\n# SHA-256: ${sha256(manifestPayload)}\n`;
const soakPayload = JSON.stringify({
    schema: 'fixture-accelerated-soak-report', version: 1, mode: 'ACCELERATED_LOGICAL_TIME',
    logicalHours: 72, cadenceMinutes: 60, logicalStartedAt: '2026-08-05T00:00:00Z',
    logicalCompletedAt: '2026-08-08T00:00:00Z', pausedHours: [20, 21, 22, 23], restartHour: 30,
    healthyHours: [12, 36, 43], unknownHours: [14, 38],
    counts: { requested: 1, started: 1, completed: 1, skipped: 0, merged: 0, failed: 0 },
    timelineEvents: 1, registryEntries: 1, activeIncidents: 1, providerConsumed: 0, providerLimit: 0,
    snapshotId: JSON.parse(manifestPayload).snapshotId,
    snapshotDirectory: `snapshots/${JSON.parse(manifestPayload).snapshotId}`, passed: true,
    verifiedInvariants: ['fixture_target_only','state_registry_timeline_verified','restart_preserved_registry',
        'provider_budget_zero','diagnosis_disabled','no_remote_or_repair_capability']
});
const soak = `${soakPayload}\n# SHA-256: ${sha256(soakPayload)}\n`;
const evaluationPayload = JSON.stringify({
    schemaVersion: 1, mode: 'FIXTURE_SYNTHETIC_CONTRACT_MATRIX', executedAt: '2026-09-20T12:00:00Z',
    targetId: 'fixture-app-down', counts: { total: 100, eligibleShadow: 20, askRequired: 47, rejected: 30, expired: 3 },
    reasonCounts: { ACTION_NOT_ALLOWED: 10, CONDITION_NOT_ACTIVE: 15, EVIDENCE_INSUFFICIENT: 8,
        EVIDENCE_STALE: 15, FIXTURE_POLICY_ELIGIBLE: 20, MODEL_OPPOSES_ACTION: 6, POLICY_DISABLED: 3,
        POLICY_EXPIRED: 3, REPAIR_POLICY_DENIED: 10, TARGET_NOT_ALLOWED: 10 },
    sideEffectCalls: 0, passed: true,
    verifiedInvariants: ['fixture_only','one_action_allowlist','side_effect_calls_zero',
        'recovered_and_insufficient_evidence_hold_ask','unsupported_actions_rejected','expired_policy_never_eligible']
});
const evaluation = `${evaluationPayload}\n# SHA-256: ${sha256(evaluationPayload)}\n`;
const a2EvidencePayload = JSON.stringify({
    schemaVersion: 1, mode: 'FIXTURE_A2_APPROVAL_CONTRACT', executedAt: '2026-09-20T12:00:00Z',
    fixtureOnly: true, remoteWrites: 0, scenarios: [
        { name: 'APPROVE_VERIFIED', simulatedApproval: true, finalStatus: 'RESOLVED', approvalPrompts: 1,
            fixtureFixCalls: 1, remoteWrites: 0, independentVerification: true, continueFixCallDelta: 0, outcomeUnknown: false },
        { name: 'OUTCOME_UNKNOWN', simulatedApproval: true, finalStatus: 'NEEDS_HUMAN', approvalPrompts: 1,
            fixtureFixCalls: 1, remoteWrites: 0, independentVerification: false, continueFixCallDelta: 0, outcomeUnknown: true }
    ]
});
const a2Evidence = `${a2EvidencePayload}\n# SHA-256: ${sha256(a2EvidencePayload)}\n`;
const a1EvidencePayload = JSON.stringify({
    schemaVersion: 1, mode: 'FIXTURE_A1_RECONCILIATION_CONTRACT', executedAt: '2026-09-20T12:00:00Z',
    fixtureOnly: true, providerCandidateSimulated: true, providerCalls: 0,
    diagnosisProvenance: { mode: 'MODEL_RECONCILED', modelCandidateRootCause: 'APP_DOWN',
        finalRootCause: 'DB_LOCK_WAIT', deterministicEvidenceChangedConclusion: true,
        deterministicEvidenceIds: ['fixture-db-lock-1'], reasonCode: 'CURRENT_EVIDENCE_OVERRIDE' }
});
const a1Evidence = `${a1EvidencePayload}\n# SHA-256: ${sha256(a1EvidencePayload)}\n`;
const shadowPolicyBody = { schemaVersion: 1, policyId: 'fixture-shadow-v1', autonomyLevel: 'A3_SHADOW',
    environment: 'FIXTURE', targetId: 'fixture-app-down', capabilityProfile: 'fixture-observe-only',
    actionCode: 'restart_service', serviceId: 'order-api', maxAttempts: 1, maxShadowDecisions: 100,
    expiresAt: '2026-09-20T13:00:00Z', enabled: true };
const shadowPolicyHash = sha256([shadowPolicyBody.schemaVersion,shadowPolicyBody.policyId,shadowPolicyBody.autonomyLevel,
    shadowPolicyBody.environment,shadowPolicyBody.targetId,shadowPolicyBody.capabilityProfile,shadowPolicyBody.actionCode,
    shadowPolicyBody.serviceId,shadowPolicyBody.maxAttempts,shadowPolicyBody.maxShadowDecisions,
    Date.parse(shadowPolicyBody.expiresAt),shadowPolicyBody.enabled].join('\n'));
const shadowPolicyPayload = JSON.stringify({ policy: shadowPolicyBody, policyHash: shadowPolicyHash });
const shadowPolicy = `${shadowPolicyPayload}\n# SHA-256: ${sha256(shadowPolicyPayload)}\n`;
const shadowSnapshotHash = sha256(manifest);
const shadowIdentity = ['inc-fixture-app-down-a89c8874','fixture-app-down',shadowSnapshotHash,shadowPolicyHash,
    'restart_service','order-api'].join('\n');
const shadowDecisionPayload = JSON.stringify({ decisionId: `shadow-${sha256(shadowIdentity).slice(0,32)}`,
    decidedAt: '2026-09-19T08:00:02Z', incidentId: 'inc-fixture-app-down-a89c8874', targetId: 'fixture-app-down',
    evidenceSnapshotHash: shadowSnapshotHash, policyHash: shadowPolicyHash, candidateAction: 'restart_service',
    candidateServiceId: 'order-api', outcome: 'ELIGIBLE_SHADOW', reasonCodes: ['FIXTURE_POLICY_ELIGIBLE'],
    modelOpinion: 'UNSPECIFIED', sideEffectCalls: 0 });
const shadowDecision = `${shadowDecisionPayload}\n# SHA-256: ${sha256(shadowDecisionPayload)}\n`;
const shadowReviewIdentity = [JSON.parse(shadowDecisionPayload).decisionId,shadowPolicyHash,shadowSnapshotHash,
    'WOULD_REJECT'].join('\n');
const shadowReviewPayload = JSON.stringify({ reviewId: `shadow-review-${sha256(shadowReviewIdentity).slice(0,32)}`,
    decisionId: JSON.parse(shadowDecisionPayload).decisionId, policyHash: shadowPolicyHash,
    evidenceSnapshotHash: shadowSnapshotHash, reviewerDecision: 'WOULD_REJECT',
    // JavaTimeModule writes Instant as epoch seconds by default; keep this fixture aligned with persisted review files.
    reviewedAt: 1758268803, sideEffectCalls: 0 });
const shadowReview = `${shadowReviewPayload}\n# SHA-256: ${sha256(shadowReviewPayload)}\n`;
const dogfoodLog = [
    JSON.stringify({ date: '2026-09-20', type: 'FRICTION', incidentId: 'ignored',
        frictionDescription: 'not rendered', frictionPriority: 'LOW', userActions: 1 }),
    JSON.stringify({ date: '2026-09-20', type: 'SHADOW_REVIEW', environment: 'FIXTURE',
        reviewId: 'shadow-review-11111111111111111111111111111111',
        decisionId: JSON.parse(shadowDecisionPayload).decisionId, policyHash: shadowPolicyHash,
        evidenceSnapshotHash: shadowSnapshotHash, reviewerDecision: 'WOULD_REJECT', sideEffectCalls: 0,
        userActions: 1 }),
    JSON.stringify({ date: '2026-09-20', type: 'SHADOW_REVIEW', environment: 'FIXTURE',
        reviewId: 'shadow-review-22222222222222222222222222222222',
        decisionId: JSON.parse(shadowDecisionPayload).decisionId, policyHash: shadowPolicyHash,
        evidenceSnapshotHash: shadowSnapshotHash, reviewerDecision: 'NEEDS_MORE_EVIDENCE', sideEffectCalls: 0,
        userActions: 1 })
].join('\n') + '\n';
const investigationResult = JSON.stringify({
    discovery: { intentionallyNotRendered: true }, diagnosis: { intentionallyNotRendered: true },
    providerCalled: true, diagnosisFailureCode: null, completedAt: '2026-09-20T12:00:00Z',
    diagnosisProvenance: { mode: 'MODEL_RECONCILED', modelCandidateRootCause: 'APP_DOWN',
        finalRootCause: 'DB_LOCK_WAIT', deterministicEvidenceChangedConclusion: true,
        deterministicEvidenceIds: ['db-lock-1', 'metric-1'], reasonCode: 'CURRENT_EVIDENCE_OVERRIDE' }
});

const elements = new Map();
function element(id) {
    if (!elements.has(id)) {
        const classes = new Set(id === 'dashboard' || id === 'error' ? ['hidden'] : []);
        elements.set(id, {
            textContent: '', innerHTML: '', listeners: {},
            classList: { add: value => classes.add(value), remove: value => classes.delete(value),
                contains: value => classes.has(value) },
            addEventListener(type, listener) { this.listeners[type] = listener; }
        });
    }
    return elements.get(id);
}

const context = {
    console, TextEncoder,
    window: { crypto: crypto.webcrypto },
    document: { getElementById: element }
};
vm.runInNewContext(scriptMatch[1], context);

const asFile = (name, text) => ({ name, size: Buffer.byteLength(text), text: async () => text });
const validFiles = () => [
    asFile('fixture-evidence-manifest.json', manifest),
    ...Object.entries(evidence).map(([name, text]) => asFile(name, text))
];
const validFilesWithSoak = () => [...validFiles(), asFile('accelerated-soak-report.json', soak)];
const validFilesWithEvaluation = () => [...validFiles(), asFile('a3-fixture-evaluation-report.json', evaluation)];
const validFilesWithA2Evidence = () => [...validFiles(), asFile('a2-fixture-evidence-report.json', a2Evidence)];
const validFilesWithA1Evidence = () => [...validFiles(), asFile('a1-fixture-reconciliation-report.json', a1Evidence)];
const validFilesWithShadow = () => [...validFiles(), asFile('shadow-policy.json', shadowPolicy),
    asFile('shadow-decision.json', shadowDecision)];
const validFilesWithShadowReview = () => [...validFilesWithShadow(), asFile('shadow-review.json', shadowReview)];
const validFilesWithDogfood = () => [...validFiles(), asFile('usage.jsonl', dogfoodLog)];
const validFilesWithInvestigation = () => [...validFiles(), asFile('investigation-result.json', investigationResult)];
const validFilesWithStatePayload = replacementStatePayload => {
    const replacementState = `${replacementStatePayload}\n# SHA-256: ${sha256(replacementStatePayload)}\n`;
    const replacementEvidence = { ...evidence, 'automation-state.json': replacementState };
    const replacementManifestPayload = JSON.stringify({
        ...JSON.parse(manifestPayload),
        files: Object.fromEntries(Object.entries(replacementEvidence).map(([name, text]) =>
            [name, { bytes: Buffer.byteLength(text), sha256: sha256(text) }]))
    });
    const replacementManifest = `${replacementManifestPayload}\n# SHA-256: ${sha256(replacementManifestPayload)}\n`;
    return [asFile('fixture-evidence-manifest.json', replacementManifest),
        ...Object.entries(replacementEvidence).map(([name, text]) => asFile(name, text))];
};
const validFilesWithTimeline = replacementTimeline => {
    const replacementEvidence = { ...evidence, 'observation-timeline.jsonl': replacementTimeline };
    const replacementManifestPayload = JSON.stringify({
        ...JSON.parse(manifestPayload),
        files: Object.fromEntries(Object.entries(replacementEvidence).map(([name, text]) =>
            [name, { bytes: Buffer.byteLength(text), sha256: sha256(text) }]))
    });
    const replacementManifest = `${replacementManifestPayload}\n# SHA-256: ${sha256(replacementManifestPayload)}\n`;
    return [asFile('fixture-evidence-manifest.json', replacementManifest),
        ...Object.entries(replacementEvidence).map(([name, text]) => asFile(name, text))];
};

async function select(files) {
    await element('bundle-files').listeners.change({ target: { files } });
}

(async () => {
    await element('synthetic-guide-button').listeners.click();
    assert.equal(element('dashboard').classList.contains('hidden'), false,
        `synthetic guide was rejected: ${element('error').textContent}`);
    assert.equal(element('synthetic-guide').classList.contains('hidden'), false,
        'synthetic guide warning must be visible while showing synthetic data');
    assert.match(element('metrics').innerHTML, /A3 合约矩阵/);
    assert.match(element('diagnosis-body').innerHTML, /模型候选 <b>APP_DOWN<\/b>/);
    assert.match(element('diagnosis-body').innerHTML, /DB_LOCK_WAIT/);

    await select(validFiles());
    assert.equal(element('dashboard').classList.contains('hidden'), false,
        `valid bundle was rejected: ${element('error').textContent}`);
    assert.equal(element('synthetic-guide').classList.contains('hidden'), true,
        'real evidence must not retain the synthetic-guide warning');
    assert.match(element('metrics').innerHTML, /证据快照/);
    assert.match(element('timeline-count').textContent, /1 条已绑定事件/);
    assert.equal(element('manifest-name').textContent, '已校验');

    await element('import-zone').listeners.drop({ preventDefault() {}, dataTransfer: { files: validFilesWithSoak() } });
    assert.equal(element('dashboard').classList.contains('hidden'), false,
        `dragged valid bundle was rejected: ${element('error').textContent}`);
    assert.match(element('soak-name').textContent, /已校验/);

    await select(validFilesWithSoak());
    assert.equal(element('dashboard').classList.contains('hidden'), false,
        `valid bundle with soak report was rejected: ${element('error').textContent}`);
    assert.match(element('metrics').innerHTML, /72h/);
    assert.match(element('metrics').innerHTML, /非自然墙钟/);

    await select(validFilesWithEvaluation());
    assert.equal(element('dashboard').classList.contains('hidden'), false,
        `valid A3 evaluation was rejected: ${element('error').textContent}`);
    assert.match(element('metrics').innerHTML, /A3 合约矩阵/);
    assert.match(element('eval-body').innerHTML, /不能推导真实运维效果/);
    assert.match(element('eval-body').innerHTML, /CONDITION_NOT_ACTIVE/);
    assert.match(element('eval-name').textContent, /已校验/);

    await select(validFilesWithA1Evidence());
    assert.equal(element('dashboard').classList.contains('hidden'), false,
        `valid A1 evidence was rejected: ${element('error').textContent}`);
    assert.match(element('diagnosis-body').innerHTML, /模型候选 <b>APP_DOWN<\/b>/);
    assert.match(element('diagnosis-body').innerHTML, /DB_LOCK_WAIT/);
    assert.match(element('a1-name').textContent, /候选已由当前证据改写/);

    const unsafeA1Payload = a1EvidencePayload.replace('"fixtureOnly":true', '"fixtureOnly":false');
    const unsafeA1Evidence = `${unsafeA1Payload}\n# SHA-256: ${sha256(unsafeA1Payload)}\n`;
    await select([...validFiles(), asFile('a1-fixture-reconciliation-report.json', unsafeA1Evidence)]);
    assert.match(element('error').textContent, /A1 Fixture 裁决证据/);
    assert.equal(element('dashboard').classList.contains('hidden'), true);

    await select(validFilesWithA2Evidence());
    assert.equal(element('dashboard').classList.contains('hidden'), false,
        `valid A2 evidence was rejected: ${element('error').textContent}`);
    assert.match(element('metrics').innerHTML, /A2 Fixture 审批/);
    assert.match(element('a2-body').innerHTML, /APPROVE → RESOLVED/);
    assert.match(element('a2-body').innerHTML, /UNKNOWN → NEEDS_HUMAN/);
    assert.match(element('a2-name').textContent, /远程写入 0/);

    const unsafeA2Payload = a2EvidencePayload.replace('"remoteWrites":0', '"remoteWrites":1');
    const unsafeA2Evidence = `${unsafeA2Payload}\n# SHA-256: ${sha256(unsafeA2Payload)}\n`;
    await select([...validFiles(), asFile('a2-fixture-evidence-report.json', unsafeA2Evidence)]);
    assert.match(element('error').textContent, /A2 Fixture 审批证据/);
    assert.equal(element('dashboard').classList.contains('hidden'), true);

    await select(validFilesWithInvestigation());
    assert.equal(element('dashboard').classList.contains('hidden'), false,
        `valid A1 provenance was rejected: ${element('error').textContent}`);
    assert.match(element('diagnosis-body').innerHTML, /模型候选 <b>APP_DOWN<\/b>/);
    assert.match(element('diagnosis-body').innerHTML, /DB_LOCK_WAIT/);
    assert.doesNotMatch(element('diagnosis-body').innerHTML, /intentionallyNotRendered/);
    assert.match(element('diagnosis-name').textContent, /已改写模型候选/);

    await select(validFilesWithShadow());
    assert.equal(element('dashboard').classList.contains('hidden'), false,
        `valid A3 decision was rejected: ${element('error').textContent}`);
    assert.match(element('decision-body').innerHTML, /本会进入 A2 审批/);
    assert.match(element('decision-body').innerHTML, /FIXTURE_POLICY_ELIGIBLE/);
    assert.match(element('decision-name').textContent, /绑定当前 A0 snapshot/);

    await select(validFilesWithShadowReview());
    assert.equal(element('dashboard').classList.contains('hidden'), false,
        `valid A3 review was rejected: ${element('error').textContent}`);
    assert.match(element('decision-body').innerHTML, /人工选择 WOULD_REJECT/);
    assert.match(element('decision-name').textContent, /review 已校验/);

    await select(validFilesWithDogfood());
    assert.equal(element('dashboard').classList.contains('hidden'), false,
        `valid dogfood reviews were rejected: ${element('error').textContent}`);
    assert.match(element('flight-body').innerHTML, /A4/);
    assert.match(element('flight-body').innerHTML, /仍需人工审批/);
    assert.match(element('metrics').innerHTML, /人工复盘/);
    assert.match(element('alignment-body').innerHTML, /会拒绝 1/);
    assert.match(element('alignment-body').innerHTML, /证据不足 1/);
    assert.doesNotMatch(element('alignment-body').innerHTML, /not rendered/);
    assert.match(element('dogfood-name').textContent, /2 条 review/);

    const unsafeDogfood = dogfoodLog.replace('"sideEffectCalls":0', '"sideEffectCalls":1');
    await select([...validFiles(), asFile('usage.jsonl', unsafeDogfood)]);
    assert.match(element('error').textContent, /零副作用契约/);
    assert.equal(element('dashboard').classList.contains('hidden'), true);

    const duplicateDogfood = dogfoodLog.replace('shadow-review-22222222222222222222222222222222', 'shadow-review-11111111111111111111111111111111');
    await select([...validFiles(), asFile('usage.jsonl', duplicateDogfood)]);
    assert.match(element('error').textContent, /零副作用契约/);
    assert.equal(element('dashboard').classList.contains('hidden'), true);

    const nonZeroProviderLimit = statePayload.replace('"maxProviderCalls":0', '"maxProviderCalls":1');
    await select(validFilesWithStatePayload(nonZeroProviderLimit));
    assert.match(element('error').textContent, /Provider 预算 0\/0/);
    assert.equal(element('dashboard').classList.contains('hidden'), true);
    assert.equal(element('manifest-name').textContent, '校验失败，请重新选择');

    const nonZeroProviderConsumed = statePayload.replace('"providerConsumed":0', '"providerConsumed":1');
    await select(validFilesWithStatePayload(nonZeroProviderConsumed));
    assert.match(element('error').textContent, /Provider 预算 0\/0/);
    assert.equal(element('dashboard').classList.contains('hidden'), true);

    const duplicatedFactEvent = JSON.parse(timeline);
    const serviceRef = duplicatedFactEvent.fields.evidenceRefs[0];
    duplicatedFactEvent.fields.evidenceRefs.push(`fixture://${runId}/http-probe`);
    duplicatedFactEvent.fields.evidenceFacts.push({ reference: serviceRef, kind: 'SERVICE_STATE', value: 'stopped' });
    await select(validFilesWithTimeline(`${JSON.stringify(duplicatedFactEvent)}\n`));
    assert.match(element('error').textContent, /Fixture A0 只读合约/);
    assert.equal(element('dashboard').classList.contains('hidden'), true);

    const wrongName = validFiles();
    wrongName[3] = { ...wrongName[3], name: 'wrong.jsonl' };
    await select(wrongName);
    assert.match(element('error').textContent, /四个快照文件/);
    assert.equal(element('dashboard').classList.contains('hidden'), true);

    const mismatchedSoakPayload = soakPayload.replace(JSON.parse(manifestPayload).snapshotId,
        'fixture-snapshot-00000000-0000-0000-0000-000000000000');
    const mismatchedSoak = `${mismatchedSoakPayload}\n# SHA-256: ${sha256(mismatchedSoakPayload)}\n`;
    const mismatchedSoakFiles = [...validFiles(), asFile('accelerated-soak-report.json', mismatchedSoak)];
    await select(mismatchedSoakFiles);
    assert.match(element('error').textContent, /没有绑定当前快照/);
    assert.equal(element('dashboard').classList.contains('hidden'), true);

    const tampered = validFiles();
    tampered[3] = asFile('observation-timeline.jsonl', `${timeline} `);
    await select(tampered);
    assert.match(element('error').textContent, /与当前 manifest 不匹配/);
    assert.equal(element('dashboard').classList.contains('hidden'), true);

    if (process.argv[2]) {
        const realBundleDirectory = path.resolve(process.argv[2]);
        const realNames = ['fixture-evidence-manifest.json', 'automation-state.json',
            'incident-registry.jsonl', 'observation-timeline.jsonl',
            'accelerated-soak-report.json'];
        const realFiles = realNames.map(name => asFile(name,
            fs.readFileSync(path.join(realBundleDirectory, name), 'utf8')));
        await select(realFiles);
        assert.equal(element('dashboard').classList.contains('hidden'), false,
            `real bundle was rejected: ${element('error').textContent}`);
        assert.match(element('metrics').innerHTML, /72h/);
        assert.match(element('timeline-count').textContent, /68 条已绑定事件/);
        process.stdout.write('console-real-bundle: PASS\n');
    }

    if (process.argv[3] && process.argv[4]) {
        const shadowDirectory = path.resolve(process.argv[3]);
        const evaluationDirectory = path.resolve(process.argv[4]);
        const snapshotDirectory = path.resolve(process.argv[2]);
        const required = ['fixture-evidence-manifest.json', 'automation-state.json',
            'incident-registry.jsonl', 'observation-timeline.jsonl', 'accelerated-soak-report.json'];
        const policyName = fs.readdirSync(path.join(shadowDirectory, 'policies')).find(name => name.endsWith('.json'));
        const decisionName = fs.readdirSync(path.join(shadowDirectory, 'decisions')).find(name => name.endsWith('.json'));
        assert.ok(policyName && decisionName, 'real A3 policy and decision must exist');
        const realA3Files = required.map(name => asFile(name, fs.readFileSync(path.join(snapshotDirectory, name), 'utf8')));
        realA3Files.push(asFile('a3-fixture-evaluation-report.json', fs.readFileSync(
            path.join(evaluationDirectory, 'a3-fixture-evaluation-report.json'), 'utf8')));
        realA3Files.push(asFile(policyName, fs.readFileSync(path.join(shadowDirectory, 'policies', policyName), 'utf8')));
        realA3Files.push(asFile(decisionName, fs.readFileSync(path.join(shadowDirectory, 'decisions', decisionName), 'utf8')));
        await select(realA3Files);
        assert.equal(element('dashboard').classList.contains('hidden'), false,
            `real A3 bundle was rejected: ${element('error').textContent}`);
        assert.match(element('decision-body').innerHTML, /为什么没有越权/);
        const realDecision = JSON.parse(fs.readFileSync(path.join(shadowDirectory, 'decisions', decisionName), 'utf8')
            .replace(/\r\n/g, '\n').split('\n')[0]);
        if (realDecision.outcome === 'ASK_REQUIRED') {
            assert.match(element('decision-body').innerHTML, /保持人工审批/);
            assert.match(element('decision-body').innerHTML, /快照观察已过期/);
        } else if (realDecision.outcome === 'ELIGIBLE_SHADOW') {
            assert.match(element('decision-body').innerHTML, /本会进入 A2 审批/);
            assert.match(element('decision-body').innerHTML, /A3 只留下候选/);
        } else {
            assert.fail(`unexpected real Fixture decision outcome: ${realDecision.outcome}`);
        }
        assert.match(element('eval-body').innerHTML, /EVIDENCE_STALE/);
        process.stdout.write('console-real-a3-bundle: PASS\n');
    }

    if (process.argv[5] && process.argv[6]) {
        const freshSnapshotDirectory = path.resolve(process.argv[5]);
        const freshShadowDirectory = path.resolve(process.argv[6]);
        const coreNames = ['fixture-evidence-manifest.json', 'automation-state.json',
            'incident-registry.jsonl', 'observation-timeline.jsonl'];
        const freshPolicyName = fs.readdirSync(path.join(freshShadowDirectory, 'policies'))
            .find(name => name.endsWith('.json'));
        const freshDecisionName = fs.readdirSync(path.join(freshShadowDirectory, 'decisions'))
            .find(name => name.endsWith('.json'));
        assert.ok(freshPolicyName && freshDecisionName, 'fresh A3 policy and decision must exist');
        const freshFiles = coreNames.map(name => asFile(name,
            fs.readFileSync(path.join(freshSnapshotDirectory, name), 'utf8')));
        freshFiles.push(asFile(freshPolicyName, fs.readFileSync(
            path.join(freshShadowDirectory, 'policies', freshPolicyName), 'utf8')));
        freshFiles.push(asFile(freshDecisionName, fs.readFileSync(
            path.join(freshShadowDirectory, 'decisions', freshDecisionName), 'utf8')));
        await select(freshFiles);
        assert.equal(element('dashboard').classList.contains('hidden'), false,
            `fresh A3 bundle was rejected: ${element('error').textContent}`);
        assert.match(element('decision-body').innerHTML, /本会进入 A2 审批/);
        assert.match(element('decision-body').innerHTML, /A3 只留下候选/);
        assert.match(element('decision-count').textContent, /ELIGIBLE_SHADOW · side effects 0/);
        process.stdout.write('console-real-fresh-a3-bundle: PASS\n');
    }

    if (process.argv[7]) {
        const snapshotDirectory = path.resolve(process.argv[2]);
        const a2Report = fs.readFileSync(path.resolve(process.argv[7]), 'utf8');
        const required = ['fixture-evidence-manifest.json', 'automation-state.json',
            'incident-registry.jsonl', 'observation-timeline.jsonl'];
        const realA2Files = required.map(name => asFile(name,
            fs.readFileSync(path.join(snapshotDirectory, name), 'utf8')));
        realA2Files.push(asFile('a2-fixture-evidence-report.json', a2Report));
        await select(realA2Files);
        assert.equal(element('dashboard').classList.contains('hidden'), false,
            `real A2 evidence was rejected: ${element('error').textContent}`);
        assert.match(element('a2-body').innerHTML, /UNKNOWN → NEEDS_HUMAN/);
        process.stdout.write('console-real-a2-bundle: PASS\n');
    }

    if (process.argv[8]) {
        const snapshotDirectory = path.resolve(process.argv[2]);
        const a1Report = fs.readFileSync(path.resolve(process.argv[8]), 'utf8');
        const required = ['fixture-evidence-manifest.json', 'automation-state.json',
            'incident-registry.jsonl', 'observation-timeline.jsonl'];
        const realA1Files = required.map(name => asFile(name,
            fs.readFileSync(path.join(snapshotDirectory, name), 'utf8')));
        realA1Files.push(asFile('a1-fixture-reconciliation-report.json', a1Report));
        await select(realA1Files);
        assert.equal(element('dashboard').classList.contains('hidden'), false,
            `real A1 evidence was rejected: ${element('error').textContent}`);
        assert.match(element('diagnosis-body').innerHTML, /DB_LOCK_WAIT/);
        process.stdout.write('console-real-a1-bundle: PASS\n');
    }

    process.stdout.write('console-contract: PASS\n');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
