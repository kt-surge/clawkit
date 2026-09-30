# CLAWKIT Observe-only Console

这是 OPS-3A Fixture-only loop 的本地证据回放页，不是远程控制台。页面顶部的 A0—A4 边界条用于讲清产品当前能力：A0 是导入证据所对应的 Fixture 只读观测；A3 已能回放 Fixture-only 的反事实判断；A4 明确是 No-Go。它们不是开关，也不会赋予任何修复权限。

先在 CLI 中停止并导出一次不可变快照：

```text
/ops observe fixture stop
/ops observe fixture snapshot
```

若要先在面试或本地演示时快速讲清界面，页面提供“加载合成导览”。它在浏览器内生成一组固定的 A0/A1/A3 Fixture 样例：包含“模型候选 APP_DOWN 被确定性 DB_LOCK_WAIT 证据改写”的 A1 裁决卡，并用醒目的黄色提示标为**非运行证据**；不读取网络或本机文件，也不能替代下文的真实快照、dogfood 或 A4 准入。

再用本机浏览器打开 `dist/index.html`，点击一次“选择快照文件”，同时选择 CLI 输出目录中的：

- `fixture-evidence-manifest.json`
- `automation-state.json`
- `incident-registry.jsonl`
- `observation-timeline.jsonl`

快照默认位于 `~/.clawkit/fixtures/ops-3a-observe-only/snapshots/<snapshot-id>/`。页面只在浏览器内解析你选择的文件；先校验 manifest 自身的 SHA-256 页脚及其绑定的三份文件字节数和哈希，再校验状态文件与 Incident Registry 的页脚，最后显示数据；没有接口地址、网络请求、任务启动、模型调用、审批或修复操作。

若要将 A2 的审批边界也作为独立证据展示，先生成一份新的空目录：

```powershell
mvn -q -pl clawkit-cli -am package -DskipTests
java -cp clawkit-cli/target/clawkit-cli-0.1.0-shaded.jar com.clawkit.ops.delivery.FixtureAskEvidenceMain <empty-output-directory>
```

把其中的 `a2-fixture-evidence-report.json` 和任意有效的 A0 四文件快照一起选择。页面会校验 SHA-256、固定的两个 Fixture 场景和 `remoteWrites=0`：一条为“模拟审批 → Fixture 内调用 → 独立验证 → RESOLVED”，另一条为“派发后 I/O 中断 → NEEDS_HUMAN → continue 重派发 0”。它不绑定 A0 snapshot，也不表示真实人工批准、远程写入或线上修复成功；`fixtureFixCalls=1` 只表示进程内 test double 被调用一次。

同样可生成 A1 的裁决证据：

```powershell
mvn -q -pl clawkit-cli -am package -DskipTests
java -cp clawkit-cli/target/clawkit-cli-0.1.0-shaded.jar com.clawkit.ops.loop.FixtureRecommendEvidenceMain <empty-output-directory>
```

导入 `a1-fixture-reconciliation-report.json` 后，现有“A1 证据裁决轨迹”卡会显示一个明确标注的 Fixture 候选：`APP_DOWN` 被当前 `fixture-db-lock-1` 证据改写为 `DB_LOCK_WAIT`。页面验证页脚、固定 taxonomy 和受限证据 ID；该候选没有调用 Provider，更不能被当作模型准确率或真实故障诊断样本。

如果要演示 72 逻辑小时的受控演练，在 loop 停止后执行：

```text
/ops observe fixture soak
```

它会在隔离的 `soak-evidence` 目录中运行真实 Fixture loop，并在该次 snapshot 目录内额外生成可选第五份 `accelerated-soak-report.json`。此时一次选择或直接拖入同目录的五份文件；页面会先校验报告自身的 SHA-256 页脚，再核对其 `snapshotId`、时间线事件数、72 逻辑小时场景、`Provider 0/0` 和计数关系。通过后四张文件卡显示“已校验”，报告显示“逻辑演练 72h · 加速时间 · 非自然墙钟”。普通四文件快照仍可独立回放；不要把普通快照与其他演练目录的报告混选。

时间线只有同时满足 Fixture A0 合约时才会显示：固定 `fixture-*` target、白名单事件类型、唯一的 `run://fixture-observe-<UUID>` 运行标识、归属于该次 run 且不重复的 `fixture://` 证据引用，以及与每条引用一一对应的白名单事实（仅服务状态 `stopped/running`、HTTP `200/503` 或采集不可用）。页面还会拒绝跨 target、时间倒序、事实与引用错配，以及与状态文件“完成 + 合并”计数不一致的回放。它不导入原始日志、命令、连接或凭据。任一记录不符合时都不会展示，避免把拼接或缺失的记录包装成演示证据。

如需展示 A3 的安全决策边界，在 loop 停止后执行：

```text
/ops observe fixture shadow-eval
```

它会在 `shadow-evaluation/` 生成独立的 `a3-fixture-evaluation-report.json`。把该文件和任意一组有效 A0 四文件快照同时选择，即可显示 A3 合约矩阵：固定 100 个**合成 Fixture**案例中 `20` 条仅为可记录的 `ELIGIBLE_SHADOW`、`47` 条保持 `ASK_REQUIRED`、`30` 条拒绝、`3` 条过期，`sideEffectCalls=0`。该报告不绑定 A0 snapshot，也不代表真实服务器、线上故障分布、人工采纳率或自动修复效果；页面会对它单独校验 SHA-256、固定计数、拒绝理由分布和安全不变量。

若要回放一条实际的 Fixture Shadow 结论，先在已有快照后执行：

```text
/ops observe fixture shadow
```

从输出目录 `shadow-evidence/<snapshot-id>/` 分别选择 `policies/` 下的一份 policy 文件、`decisions/` 下的一份 decision 文件，并与**产生它的同一份** A0 四文件快照一起导入。Console 会单独校验两份文件页脚、policy 内容哈希、deterministic decision ID，以及 decision 中的 evidence hash 是否精确绑定所选 manifest；通过后才显示“本会进入 A2 审批（未执行）”“保持人工审批”等反事实判断和 `side effects 0`。若使用交互 CLI 执行过 `/ops observe fixture shadow-review <approve|reject|defer>`，还可额外导入 `reviews/` 下的单份 review：页面会校验它绑定同一 decision、policy 和 evidence，再显示人工的**反事实**选择。它不是审批、不会创建 ApprovalGrant，也不代表修复执行或验证结果。页面始终不提供批准、重放、修复或 Policy 编辑入口；若混入其他 snapshot，会拒绝展示。

回放卡还会把机器原因码翻译成“为什么没有越权”的安全刹车说明：例如旧快照会显示“证据已过期，必须重新观察”，不在白名单的动作会显示“停在 A2”。页面上方的“分层自治飞行记录”会把本次导入在 A0–A4 的位置串起来：A1 未导入不会被伪装成诊断，A2 始终标为待人工审批，A4 恒为 No-Go。这只是已校验证据的解释层，不能替代 A2 的现场复查、人工审批或独立验证。

还可选择本机 `~/.clawkit/dogfood/usage.jsonl`。页面只统计其中 `SHADOW_REVIEW` 行的“会同意 / 会拒绝 / 证据不足”，不展示其他任务或反馈正文；每条 review 必须是 Fixture、带唯一且合法的不可变 `reviewId`、decision/policy/evidence hash，且 `sideEffectCalls=0`，否则整个导入拒绝。重复 `reviewId` 也会拒绝，避免重放或手工拼接虚增样本。该日志不绑定当前 snapshot、没有签名，只是后续人工选择对账的本地趋势信号，不能当成自动修复成功率、线上样本或 A4 准入证据。

若有一次本地调查的 `investigation-result.json`，也可和 A0 快照一起导入。Console 只读取其中的 `diagnosisProvenance`：模型候选 taxonomy、最终 taxonomy、确定性证据 ID 与裁决原因；发生冲突时会展示“模型候选 → 当前证据裁决 → 最终结论”。它不会渲染模型正文、连接信息、原始日志或报告内容。该调查结果是原子落盘的本地 JSON，当前没有签名页脚，因此 Console 只做严格字段/安全边界校验；它用于走读一次裁决，而不是单独作为线上效果或 A4 准入证据。

在没有可交互终端的 CI/演示环境，可用 `FixtureAutonomyEvidenceMain <output-directory>` 一次生成 A0 soak、对应的过期 A3 Shadow、新鲜 A0 snapshot 的 `ELIGIBLE_SHADOW`（仅候选、零副作用）和 A3 评测文件；完整命令见 [`docs/ops-3a-fixture-demo.md`](../docs/ops-3a-fixture-demo.md#非交互一键证据复现)。它把“旧快照不能提升权限”和“新鲜双证据最多进入 A2 审批候选”并排展示，不能理解为自动修复。

修改页面后运行零依赖契约测试：

```powershell
node test/console-contract.test.js
```

测试会生成内存快照并覆盖四文件、五文件、A1 调查裁决、A2 Fixture 审批证据、A3 评测报告、policy/decision/review、可选 dogfood review 趋势导入、成功渲染、错误文件名、内容篡改、跨 snapshot 的报告拒绝、页面无网络入口，以及页面仅保留“加载合成导览”按钮、没有表单/审批/修复/策略编辑控制。要用真实加速演练快照复验 Console，可把其 snapshot 目录作为第二个参数传入该命令。
