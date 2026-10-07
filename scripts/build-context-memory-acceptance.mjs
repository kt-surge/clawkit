import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const data = JSON.parse(await readFile(resolve(root, 'benchmarks/context-memory-live-full-v1.json'), 'utf8'));
data.version = 'context-memory-live-full-acceptance-v2';
data.completionAcceptance = 'PUBLIC_WORKFLOW_ACCEPTANCE_V1';
const disclosure = "\nApplication completion acceptance (PUBLIC_WORKFLOW_ACCEPTANCE_V1): on proposed completion a local read-only check validates every declared preview and summary against these public rules and current source bytes. A rejection returns at most eight output paths, JSON pointers, rule codes and source references, never expected values. At most two corrective continuations share the original request/token/tool/deadline limits. Protected sources must remain unchanged. Independent scoring, actual source-read freshness and the pressure/turn gates are still separate.\n";
for (const task of data.tasks) {
  const path = task.environmentProgram.kind === 'CONFIG_MIGRATION' ? 'docs/migration.md' : 'docs/decisions.md';
  task.agentInput.workspaceFiles[path] += disclosure;
}
await writeFile(resolve(root, 'benchmarks/context-memory-live-full-acceptance-v2.json'), JSON.stringify(data, null, 2) + '\n');
