# OPS 分层自治：审计与 A3 Shadow 实施基线

> 2026-09-30：本文件保留 A3 审计合同与历史进展；用户已确认的后续分层自治开发见 [实施合同](layered-autonomy-implementation-plan.md)。隔离环境 P0—P4 不再等待固定七天个人 dogfood；新增有限自动修复仍需代码与运行证据，真实目标授权仍独立确认。下文的 No-Go 描述当前旧路径，不禁止按新合同实现隔离能力。

> 建立日期：2026-09-20
>
> 目标：先复核 A0—A2 的真实边界和可复现证据，再在不派发任何写操作的前提下实现 A3 Shadow。A4 保持 No-Go，不能用通用 `PermissionMode.AUTO`、测试 `--auto-approve` 或历史 Fixture E2E 替代。

## 0. 范围与硬边界

- A0 的持续运行仅接受 `fixture-*` target，只产生本地 `fixture://` 证据；不得接入真实服务器 Cron、Probe、告警、Provider 或修复能力。
- A2 的真实服务器路径保持只读；唯一允许写闭环仍是可丢弃 Fixture 中固定的 `restart_service(order-api)`，并继续经过审批、fresh precheck、Attempt Journal 和独立验证。
- A3 的职责是记录“在固定策略下本会如何处置”，而不是跳过审批。A3 运行的 `opsfix` 调用数必须为零。
- A4 只能在可丢弃 Fixture 评审，不开放任意 Shell、sudo、数据库会话终止、发布切换或第二个动作。

## 1. 当前事实与审计问题

| 层级 | 已有实现或证据 | 本轮需要独立复核的问题 | 当前结论 |
| --- | --- | --- | --- |
| A0 Observe | Fixture loop、Registry、状态恢复、timeline、快照、72 逻辑小时报告和离线 Console | 是否仍无 remote / repair / Provider 接入；状态、timeline 与 Console 是否同一契约 | 已有实现，需回归审计 |
| A1 Recommend | 诊断候选、确定性信号与协调逻辑 | 模型原始结论与协调后结论是否可区分；建议是否仍不可执行 | 已有实现，需边界审计 |
| A2 Ask | 策略门禁、ApprovalGrant、fresh precheck、durable intent、结果未知 sticky、独立验证 | 拒绝/漂移/未知结果是否都保证零重复写；Fixture 与真实只读目标是否被混写 | 已有实现，需安全审计 |
| A3 Shadow | Fixture-only 策略、纯决策门禁、不可变策略/决策/复盘存储、CLI 快照回放、100 例合约矩阵 | 是否能完成真实 dogfood、人工决策对账和单条决策动态回放 | S1/S2 与 S3 合成评测、单条人工反事实记录已实现；真实样本未开始 |
| A4 Limited Auto | 无实现 | 是否满足 A3、产品使用、评测和降级门禁 | No-Go |

审计不是只看绿测。每一层都要核对源码入口、失败分支、测试覆盖、可复现运行证据和不能扩大的表述。

## 2. 本轮执行顺序

### S0：A0—A2 证据审计

1. 建立“要求 → 源码入口 → 测试 → 运行证据 → 简历表述边界”映射；
2. 对 A0 验证固定 Fixture target、Provider `0/0`、无修复依赖、暂停/恢复/重启、UNKNOWN 不关闭 Incident、快照与 Console 拒绝篡改；
3. 对 A1 验证模型候选与确定性协调结果分开保存和统计；
4. 对 A2 验证拒绝、过期审批、precheck 漂移、执行超时、`OUTCOME_UNKNOWN`、验证失败和恢复路径；
5. 将发现的问题归属到对应模块，不用“评审遗留”代替工程任务。

**退出条件：** 每条结论都有当前源码和测试证据；未知或未覆盖项明确列为阻断，不用于简历。

**2026-09-20 独立复核结果：**

| 层级与结论 | 源码入口 | 已复核测试 | 简历允许的说法 / 禁止扩大 |
| --- | --- | --- | --- |
| A0 只自动观察 | `automation/FixtureObserveOnlyLoop` 固定 `fixture-*`、`order-api` 和 Provider `0/0`；`FixtureObservationTimelineReader` 对 target、run、证据引用、时间、诊断字段及白名单事实投影 fail-closed | `FixtureObserveOnlyLoopTest`、`FixtureEvidenceSnapshotTest`、`FixtureShadowReplayRunnerTest` | 可说“Fixture-only 持续观察、去重、快照与离线回放”；不能说接入真实告警、远程 Cron 或持续模型诊断 |
| A1 模型只提出诊断 | `DeepSeekDiagnosisGate` 拒绝伪造/陈旧证据与 `claimedResolved=true`；所有入口经 `ReconciledDiagnosis` 以当前证据校正候选，`APP_DOWN` 必须同时具备服务/容器异常与 HTTP 非 200；`DiagnosisProvenance` 将模型候选、最终 taxonomy、证据 ID 与是否改写结论写入 `investigation-result.json`，且只在发生冲突时写入中文报告；Provider 未配置/失败时报告明确标为“仅当前确定性证据” | `DeepSeekDiagnosisGateTest`、`DiagnosticSignalsTest`、`DiagnosisReconcilerTest`（含“高置信 APP_DOWN 被 DB_LOCK_WAIT 信号推翻”、无 Provider 仍走当前证据和持久化轨迹断言）、`DiagnosisReportProvenanceTest`、`RemoteDiscoveryMainTest` | 可说“模型诊断受证据 schema 与确定性规则校准，裁决轨迹可审计，建议不可执行”；不能说模型自主决定修复 |
| A2 写入仍是 ASK | `RepairPolicyGate` 除固定 action/service/根因外，还要求 `APP_DOWN` 为 ACTIVE 且至少两条当前支持证据；`RepairOrchestrator` 和 `IndependentVerifier` 的采集、freshness 判断统一使用同一工作流时钟，先 fresh precheck、快照/Grant 校验、耐久 intent，再调用独立 `FixSession`；intent 后的 I/O 与运行时派发异常都进入 `OUTCOME_UNKNOWN`；验证由新只读会话完成。通用 `PermissionMode.AUTO` 不挂载 opsfix：`RemoteMcpSession` 只接受只读标注的远程工具，`restart_service` 不属于远程只读 profile | `RepairPolicyGateTest`、`AppDownApproveFixtureTest`、`AppDownRejectFixtureTest` 覆盖双证据/ACTIVE、拒绝、自恢复、漂移、执行失败、I/O/运行时未知结果 sticky、验证失败；`FixtureAskEvidenceMainTest` 额外生成可校验的“验证成功 / 结果未知不重派发” Fixture 报告；`ToolScopeExecutionTest`、`Remote0CliE2ETest` 覆盖 read-only scope 和禁止工具未挂载 | 可说“可丢弃 Fixture 中的审批修复闭环”；不能说真实服务器已开放写入或已经自动恢复生产故障 |

复现命令使用反应堆构建，以确保同一工作区的依赖模块都参与编译和测试：

```powershell
mvn -q -pl clawkit-cli,extensions/clawkit-ops-loop,extensions/clawkit-ops-delivery -am "-Dtest=DiagnosisReconcilerTest,DeepSeekDiagnosisGateTest,FixtureObserveOnlyLoopTest,AppDownApproveFixtureTest,AppDownRejectFixtureTest,ToolScopeExecutionTest,Remote0CliE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

本次运行通过。直接在未安装 sibling 依赖的情况下单独运行 delivery leaf module 会使用本地 Maven 缓存而失败；这不是 A2 通过证据，统一以 `-am` 反应堆命令作为可复现入口。

### S1：A3 Policy 与决策契约

新增版本化、不可变的 `AutoRemediationPolicy`，最小字段为：

```text
schemaVersion, policyId, policyHash, autonomyLevel,
environment, targetClass, capabilityProfile, actionAllowlist,
maxAttempts, budget, expiresAt, enabled
```

- 首个策略只能允许 `APP_DOWN + fixture-app-down + restart_service(order-api)`；
- Policy、target、profile、action 和参数哈希必须同时匹配；模型不能生成或修改 Policy；
- 缺失、过期、hash 不一致、证据不足、状态漂移、预算耗尽、模型明确反对或结果未知时只产生 `ASK_REQUIRED` / `REJECTED`；
- Policy store 损坏或并发写入异常时 fail-closed，并全局降级为 A2。

**2026-09-20 进展：** 已实现 `AutoRemediationPolicy`、`ShadowEvaluationRequest`、`ShadowDecision` 和 `ShadowPolicyGate`。当前只接受 `FIXTURE + fixture-app-down + restart_service(order-api)` 的 A3 policy，稳定 `policyHash` 绑定全部策略字段；过期、禁用、目标不符、证据过期、模型反对和既有 Repair Policy 拒绝均不会形成可派发动作。决策 ID 由 incident、证据、策略和候选动作确定性生成。

### S2：A3 Shadow 决策与持久化

新增 append-only、可校验的 `ShadowDecision`，至少记录：

```text
decisionId, observedAt, incidentId, targetId, evidenceSnapshotHash,
policyHash, candidateAction, actionParameterHash, outcome, reasonCodes,
modelOpinion, humanDecision, verificationState, sideEffectCalls
```

`outcome` 只能是 `ELIGIBLE_SHADOW`、`ASK_REQUIRED`、`REJECTED` 或 `EXPIRED`。它不能返回可直接调用的 FixSession，也不能持有 opsfix client。对同一个 incident + snapshot + policy + action 必须幂等。

**核心不变量：** 任意 A3 运行 `opsfix/restart/write` 调用为 0；没有 fresh evidence 的旧 Shadow 决策不能升级为 A4；人工批准后仍重新走完整 A2，而不是重放 Shadow 结果。

**2026-09-20 进展：** `ShadowPolicyStore`、`ShadowDecisionStore` 与 `ShadowReviewStore` 采用“内容哈希 + SHA-256 页脚 + force(true) + 新文件原子落盘”的本地不可变格式，三者都在存在性检查前取得 JVM 内 `ReentrantLock` 与 OS `FileLock`。`ShadowWorkflow` 固化策略后才记录结论；同一身份重放返回既有记录，新身份达到 policy 的 `maxShadowDecisions` 后降级为 `ASK_REQUIRED`。A3 与 A2 复用同一 `RepairPolicyGate`：Fixture `APP_DOWN` 也必须 ACTIVE、两条支持证据齐全；快照回放会核对 ACTIVE Incident 的本次 `service-status` 与 `http-probe` 引用均属于同一 `lastRunId`，并要求该次已签名 A0 timeline 的白名单事实明确为 `SERVICE_STATE=stopped`、`HTTP_STATUS=503`，任一缺失、错配或改为 `running` 都拒绝记录。投影不含原始日志、命令、连接或凭据。决策库将“读、计数、落盘”置于同一锁事务，两个独立 Workflow 并发争夺额度时只允许一个 `ELIGIBLE_SHADOW`；其公开 `record()` 入口也先取得同一锁，防止未来绕过 Workflow 的直接持久化破坏不可变性。policy 的并发首写只复用校验后的同一内容地址；review 同一身份并发重放只生成一次、不同人工选择无法覆盖先到记录。仍只适用于本地 Fixture 存储，不可描述为生产级分布式配额或租约系统。

### S3：A3 Fixture 评测与产品证据

- 固定矩阵至少 100 次，包含 APP_DOWN 正例、自恢复、DB_LOCK_WAIT、缺证据、目标错误、过期证据、profile 漂移、预算耗尽和传输中断；
- 分别报告候选数、拒绝数、ASK 保留数、人工同意/拒绝、precheck 漂移、验证、假阳性、越权、重复副作用、结果未知和人工接管；
- 合成 Fixture 只证明契约覆盖；至少一周真实 dogfood 只记录脱敏操作和人工选择，不开启真实写入。手动 `shadow-review` 会以独立 `SHADOW_REVIEW` 事件写入本地 dogfood 日志，保留唯一不可变 `reviewId`、decision、policy/evidence hash、选择和 `sideEffectCalls=0`，不写诊断正文或连接信息；
- Console 只在 A3 已有持久化证据后展示 Shadow 决策、理由和 `sideEffectCalls=0`，不提供控制按钮。

**退出条件：** `sideEffectCalls=0`、越权=0、重复副作用=0、未验证成功=0；拒绝、冲突和未知结果均保持 A2，不以“通过率”掩盖样本结构。`/ops dogfood status` 只报告脱敏日志的采集健康度（有效/损坏条目、日期连续性与聚合选择）；满 7 个连续记录日也不是 A3/A4 自动准入，仍需人工审计样本结构与独立验证。

**2026-09-20 进展：** `/ops observe fixture shadow` 会停止后读取最新一份已校验 A0 snapshot，将其 manifest 哈希、ACTIVE Incident 和最后观察时间绑定到独立 `shadow-evidence/<snapshot-id>/` 中的 policy/decision。该桥接不写回 snapshot、不创建远程会话，CLI 显式输出 `Side effects: 0`。`/ops observe fixture shadow-eval` 额外生成固定 100 例的合成 Fixture 合约矩阵：`20` 个 `ELIGIBLE_SHADOW`、`47` 个 `ASK_REQUIRED`、`30` 个 `REJECTED`、`3` 个 `EXPIRED`，副作用为零，报告有 SHA-256 页脚且 Console 可离线复核。`shadow-review <approve|reject|defer>` 可为最新单条 decision 追加一个不可变、哈希绑定的人工**反事实**选择，仍不创建 ApprovalGrant 或修复；只有 review 首次持久化创建时才追加脱敏 `SHADOW_REVIEW` dogfood 事件，重复命令只回放既有选择，不增加人工样本计数。它们不等于线上故障样本、人工对账或真实 dogfood；真实人工结果和 dogfood 仍未完成。

**可复现运行证据（2026-09-20）：** 非交互 `FixtureAutonomyEvidenceMain` 已从真实代码路径同时生成 A0 72 逻辑小时 snapshot、其过期 A3 decision、一个新鲜双证据 A0 snapshot、其 `ELIGIBLE_SHADOW` decision，以及 A3 100 例报告。前者返回 `ASK_REQUIRED / EVIDENCE_STALE`，因为最后 ACTIVE 观察已超过五分钟窗口；后者只返回“可进入 A2 审批”的零副作用反事实候选，绝不派发修复。两者 `sideEffects=0`，共同证明“旧证据不升级、即使新鲜候选也不越过 A2”，不得改写为自动修复成功。

**A2 独立可复现证据（2026-09-20）：** `FixtureAskEvidenceMain` 已由 `clawkit-cli` shaded 包生成 `tmp/a2-fixture-evidence-20260920-r2/a2-fixture-evidence-report.json`。报告有 SHA-256 页脚，只运行进程内 read/fix Fixture double：`APPROVE_VERIFIED` 为脚本化批准 1 次、Fixture fix 调用 1 次、独立验证后 `RESOLVED`；`OUTCOME_UNKNOWN` 为 Fixture I/O 中断后 `NEEDS_HUMAN`、首次 fix 调用 1 次、`continueFixCallDelta=0`。根与每个场景均为 `remoteWrites=0`。Console 已用真实文件导入并重新校验；它证明的是 A2 编排、时钟一致性和“不重派发”失败语义，不是人工批准或真实远程修复。

**A1 独立可复现证据（2026-09-20）：** `FixtureRecommendEvidenceMain` 已由同一 shaded 包生成 `tmp/a1-fixture-evidence-20260920-r2/a1-fixture-reconciliation-report.json`。报告有 SHA-256 页脚，候选 `APP_DOWN` 是显式标注的 Fixture 输入，并以签名字段 `providerCalls=0` 固化未调用 Provider；真实 `ReconciledDiagnosis` 从当前 `fixture-db-lock-1` 事实抽取 `DB_LOCK_WAIT`，并以 `MODEL_RECONCILED / CURRENT_EVIDENCE_OVERRIDE` 记录候选被改写。Console 已用真实文件复核 taxonomy、受限证据 ID、Provider 计数与页脚。它证明的是确定性协调与安全留痕，不是模型诊断准确率或真实故障样本。

**本轮复现（双侧门禁）：** `tmp/fixture-autonomy-evidence-20260920-two-sided/` 由当前源码重新生成；soak A0 快照最后 ACTIVE run 有同一 `lastRunId` 下的 `2` 条 `fixture://` 引用，且受 manifest 绑定的 timeline 明确记录 `SERVICE_STATE=stopped`、`HTTP_STATUS=503`，其 A3 replay 为 `ASK_REQUIRED / EVIDENCE_STALE`、`sideEffects=0`。同次入口还生成新鲜双证据 snapshot，其 A3 replay 为 `ELIGIBLE_SHADOW`、`sideEffects=0`，含义仅为“可进入 A2 审批候选”。独立 100 例矩阵为 `20/47/30/3`（eligible/ask/rejected/expired）、`sideEffectCalls=0`。Console 已分别对实际过期与新鲜 A0/A3 文件完成离线导入校验。这仍是本地合成 Fixture 证据，不能替代真实 dogfood、人工对账或 A4 准入。

### S3.5：自治证据回放（Console 产品面）

在 A3 已有真实持久化记录后，扩展离线 Console 为“反事实回放”，而不是控制台：

- 一张 Policy 卡显示 `policyHash`、适用 Fixture target、唯一候选动作、预算和失效时间；
- 一张 Shadow Decision 卡同时显示“本会执行 / 保持 ASK / 已拒绝”、规则理由、模型意见、证据新鲜度和 `sideEffectCalls=0`；
- 单条卡可选展示“观察事实 → 候选动作 → Shadow 判断 → 人工反事实选择”；独立验证只属于 A2，不能伪造成 Shadow 结果；
- 提供固定的安全反例回放：自恢复、证据缺失、快照漂移与结果未知均清楚解释为什么没有升级；
- 页面继续只读、离线、无 Policy 编辑、无批准按钮、无修复按钮和无网络请求。

这会把分层自治从抽象术语变成可演示的“系统为什么没有越权”的产品证据，也可作为面试时的 90 秒走读入口。

**2026-09-20 进展：** Console 已加入静态“自治边界条”和按本次导入动态生成的“分层自治飞行记录”，将 A0 Fixture 观测、A1 建议、A2 审批、A3 只记录反事实和 A4 No-Go 放在同一视线内；A1 未导入不会被伪装成诊断，A2 恒显示待人工审批，A4 恒为 No-Go。时间线同时渲染与引用一一对应的白名单事实（如服务状态、HTTP 状态），不渲染原始输出。它能额外离线导入并严格校验 A3 100 例合约报告，展示结果计数与拒绝理由，但该报告明确独立于 A0 snapshot。`investigation-result.json` 可选展示 A1 的脱敏 `DiagnosisProvenance`：只读 taxonomy、证据 ID 和裁决原因，发生冲突时清楚说明“模型候选被当前证据改写”；不渲染模型正文、连接信息或原始日志。对于一对 policy + decision 文件，页面还会复算 policy 内容哈希和 decision ID，并要求其证据哈希精确绑定当前所选 A0 manifest，才渲染单条反事实卡；可选 review 还必须绑定同一 decision、policy、evidence 才显示人工反事实选择。还可选读本机 dogfood JSONL 中严格校验的 `SHADOW_REVIEW` 行，展示同意/拒绝/证据不足趋势，并要求唯一 `reviewId`，重复或缺失即拒绝；它不读取其他正文、不绑定当前 snapshot、无签名，不能充当质量门槛。仍没有控制按钮，也没有将任何选择伪装为 A2 审批或独立验证。

### S4：A4 Fixture-only 评审（暂不实施）

只有 S0—S3、PRODUCT-3 至少一周 dogfood 和 OPS-3A 的连续运行证据都达标后，才评审唯一动作的 A4。若批准，仍必须：`maxAttempts=1`、持久化 intent、目标互斥、fresh precheck、独立验证、全局 kill switch、自动降级 A2。

任何 Verification 失败、状态/Policy/profile 漂移、预算耗尽、补偿失败或 `OUTCOME_UNKNOWN` 都必须立即持久化降级，且禁止自动重试写操作。

## 2.5 当前完成审计（2026-09-20）

这张表按目标逐项判断，不用“测试已绿”替代尚未发生的真实使用事实。

| 目标 | 当前可证明的证据 | 当前状态 | 不能据此声称 |
| --- | --- | --- | --- |
| A0 Observe | Fixture-only target、Provider `0/0`、暂停/恢复/重启/UNKNOWN、快照及离线 Console 都有源码和回归；实际 72 **逻辑**小时 artifact 已能被 Console 复核 | Fixture 闭环已验证 | 真实服务器 Cron、外部告警或 72 自然小时运行 |
| A1 Recommend | 证据 schema、模型候选、确定性协调与脱敏 `DiagnosisProvenance` 分离；冲突信号能推翻高置信模型结论并在结果中留痕，`APP_DOWN` 需服务异常与 HTTP 失败双证据 | 代码、定向测试与报告渲染已验证 | 模型自主决定修复或模型准确率结论 |
| A2 Ask | ApprovalGrant、fresh precheck、durable intent、`OUTCOME_UNKNOWN` sticky 和独立验证都有 Fixture 失败路径测试；远程 profile 只挂只读工具，普通 CLI 不再因存在 `CLAWKIT_REMOTE_FIX_*` 自动装配写会话，独立远程 repair Main 也 fail-closed。2026-09-20 以 CLI `/auto` 连接真实 `test-server` 后实际 `tools/list` 为 10/10 只读，未出现 repair/restart 工具 | Fixture 审批闭环已验证；真实写入保持关闭 | 真实服务器已开放写或已自动恢复故障 |
| A3 Shadow | Fixture-only policy/gate、100 例合成矩阵、policy/decision/review 不可变存储、并发锁、零副作用回放和 Console 展示都已验证 | Fixture 契约与产品回放已验证 | 真实故障分布、人工采纳率、自动修复效果 |
| 真实 dogfood | `/ops dogfood status` 当前聚合为 7 条有效事件、3 个记录日、5 条调查、2 条反馈、0 条 A3 review；日志无损坏行。2026-09-20 的真实远端只读调查采集 8 项证据，发现 `order-api` 运行但 unhealthy；根因证据不足时返回人工排查建议，未出现审批或写操作。同日两次后续回放只验证终端展示，不计为独立故障样本 | **未达标** | 一周使用、人工对账或 A4 评审条件 |
| A4 Limited Auto | `AutoRemediationPolicy` 构造期只接受 A3；没有 A4 executor、控制按钮或远程 write 通路 | **No-Go** | 已具备有限自动修复 |

因此本阶段的完成定义是“安全的 Fixture A0—A3 工程闭环和诚实可演示证据”，不是整个分层自治目标完成。下一次状态变化只能来自真实墙钟/人工使用数据和独立人工审计；在此之前不创建 A4 实现、不放开远程写入，也不把逻辑时间或合成样本改写成线上效果。

**2026-09-20 dogfood 跟进：** 五条真实只读任务中，早期两次反馈和本次 `order-api` unhealthy 调查都暴露出同一信息密度问题：日志虽已在受限窗口内采集，但空日志、健康检查失败原因和其他缺口被终端压成笼统的“人工登录”。现已只改展示层：不确定结论会展示最多八项已脱敏事实，并把 `missingEvidence` 或当前 unhealthy/空日志事实映射为固定的健康检查、应用日志、资源、连接池、锁等待或重启历史类别；模型自由文本不进入终端。当前版本已在同日的两次真实只读回放中分别复核完整事实与缺口提示，但它们是同一类问题的回归，不是额外独立故障样本；仍需后续用户反馈才能说明摩擦已解决，也不改变 A3/A4 门槛。

## 3. 简历与面试口径

当前可写的是 A0—A2，不写 A3/A4：

> 在远程运维场景将自治拆分为 A0 只读观察、A1 诊断建议与 A2 审批执行；写动作绑定现场快照，经人工审批、执行前复查、结果未知阻断和独立验证后收敛。A0 自动运行仅限 Fixture，真实服务器保持只读。

在 S3 验收前，不使用“具备影子自动化”“支持有限自动修复”“自动恢复生产故障”等表述。A3 完成后也只可写“记录并评估候选动作，副作用为零”，不能写“自动修复”。

## 4. 反方审查清单

- `PermissionMode.AUTO` 是通用工具权限模式，不能作为 A4 证据；
- 历史 `--auto-approve` 仅属于 Fixture 测试语义，不能进入产品路径；当前独立远程 repair 启动器也必须 fail-closed；
- 72 逻辑小时 Fixture soak 不能替代自然墙钟运行，也不能替代 Shadow 样本；
- A0 当前 Provider 为 `0/0`，不能把其描述为“持续模型诊断”；
- 通过执行返回不等于修复成功；没有新会话验证不得记为成功；
- 历史 20/20 Fixture 审批修复与 A0 72 逻辑小时是不同样本，不能合并成一个通过率。
