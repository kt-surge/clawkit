# OPS-3A Fixture-only 闭环演示与表述口径

## 目标

用一条本地、只读、可回放的 A0 流程说明 CLAWKIT 的分层自治边界：持续观测负责发现与状态收敛；Incident Registry 负责去重；预算与策略负责限制能力；修复仍留在独立的 ASK 路径，未由本流程触发。

这不是真实服务器演示，也不是自动修复演示。

## 现场演示

先构建 CLI：

```powershell
mvn -q -pl clawkit-cli -am package -DskipTests
```

为避免使用既有个人状态，使用单独目录启动 CLI。以下 `demo-home` 和 `demo-work` 应替换成一个新的本地临时目录：

```powershell
$env:CLAWKIT_API_KEY = 'fixture-demo-no-network'
java "-Duser.home=<demo-home>" -jar clawkit-cli/target/clawkit-cli-0.1.0.jar "--root=<demo-work>"
```

在 CLI 内依次输入：

```text
/ops observe fixture start
/ops observe fixture status
/ops observe fixture healthy
```

等待一个 30 秒调度间隔后继续：

```text
/ops observe fixture status
/ops observe fixture stop
/ops observe fixture replay
/ops observe fixture snapshot
/ops observe fixture shadow
/ops observe fixture shadow-eval
/ops observe fixture shadow-review defer
```

应检查以下事实，而不是只展示一段模型文本：

- `Provider budget: 0/0`，且没有远程 target、审批或修复入口。
- 第一次状态为 `APP_DOWN` 时创建一个 Incident；健康状态的下一次观测将其关闭。
- replay 包含 `INCIDENT_CREATED` 与 `HEALTHY_RECOVERED`，每条只含 `fixture://` 证据引用。
- CLI 输出唯一的 `fixture-snapshot-<UUID>` 目录；目录内有 `fixture-evidence-manifest.json`、`automation-state.json`、`incident-registry.jsonl` 与 `observation-timeline.jsonl`。
- Shadow 输出位于独立的 `shadow-evidence/<snapshot-id>/`，只含策略和反事实决策记录；必须显示 `Side effects: 0`，不能出现修复结果或远程调用。
- Shadow-eval 输出独立的 100 例合成 Fixture 合约报告；必须显示 `20/47/30/3`（eligible/ask/rejected/expired）和 `Side effects: 0`。它检验拒绝边界，不是线上故障样本或自动修复成功率。
- `shadow-review` 只能为最新一条 Shadow decision 记录 `WOULD_APPROVE`、`WOULD_REJECT` 或 `NEEDS_MORE_EVIDENCE`；输出在同目录 `reviews/` 下，仍为 `Side effects: 0`，不会生成 ApprovalGrant、修复或验证记录。
- 由用户在交互 CLI 输入的 `shadow-review` 会额外向 `~/.clawkit/dogfood/usage.jsonl` 追加一条脱敏 `SHADOW_REVIEW` 事件，记录唯一不可变 `reviewId`、decision/policy/evidence hash、选择和副作用计数；一键证据生成器不会伪造这条人工记录。

使用 `/ops dogfood status` 可只读查看日志的有效/损坏记录、记录天数、调查/反馈/A3 review 聚合和三种人工选择计数。它不输出 target、Incident、证据正文或反馈文本；即使显示 7 个连续记录日，也只是提示可进行人工审计，不得自动视为 A3 通过或 A4 准入。

重启去重可用第二次 CLI 进程复用同一个 `<demo-home>` 验证：再次 `start` 后应看到同一
ACTIVE Incident 被 `INCIDENT_MERGED`，但新的 `run://fixture-observe-<UUID>` 与
`fixture://fixture-observe-<UUID>/...` 引用必须不同于重启前的记录。

随后在本机浏览器打开 `ops-console/dist/index.html`，一次选择或直接拖入同一 snapshot 目录内的四份文件。页面会先用 manifest 核对三份证据的精确字节数和 SHA-256，拒绝缺文件、文件名错误与跨批次混搭，再执行状态、Registry 与时间线语义校验；通过后文件卡会显示“已校验”。页面只能做离线回放；没有网络请求、启动/暂停控制、模型、审批或修复按钮。

若需要一并展示 A2 的审批修复边界，先使用 CLI shaded 包生成一个新的空目录：

```powershell
mvn -q -pl clawkit-cli -am package -DskipTests
java -cp clawkit-cli/target/clawkit-cli-0.1.0-shaded.jar com.clawkit.ops.delivery.FixtureAskEvidenceMain <empty-output-directory>
```

将其中的 `a2-fixture-evidence-report.json` 与任意有效 A0 四文件快照一起导入。它固定验证两条进程内 Fixture 路径：模拟审批后独立验证进入 `RESOLVED`，以及派发后 I/O 中断进入 `NEEDS_HUMAN` 且 `continue` 不会重派发；每条都标有 `remoteWrites=0`。它不是人工审批记录、真实 SSH 写入或线上恢复数据。

如果刚执行过 `shadow`，可把它输出目录下的一份 policy 和一份 decision 文件随这四份 A0 文件一同选择。页面只有在 decision 的证据 hash 精确匹配这次 manifest、policy 内容 hash 和 decision ID 均可复算、`sideEffectCalls=0` 时才显示反事实结论；因此不能把另一次 snapshot 的 Shadow 记录拼进演示。若执行过 `shadow-review`，可再导入同目录一份 review，页面会校验其与 decision、policy、evidence 的绑定后展示人工的反事实选择；这不等于 A2 审批或真实修复结果。

如需查看跨多次手动 review 的选择趋势，可再导入本机 `~/.clawkit/dogfood/usage.jsonl`。Console 只读取其中 `SHADOW_REVIEW` 行并统计同意、拒绝和证据不足；日志中的任务、反馈正文均不展示。任一 review 不满足 Fixture、唯一合法的 `reviewId`、hash 格式或 `sideEffectCalls=0` 即拒绝导入，重复 ID 不计入样本。该 JSONL 是未签名的本地 dogfood 记录，不绑定当前 snapshot，不得作为线上样本、自动修复效果或 A4 准入证据。

### 加速 soak 演示

保持 loop 已停止，输入：

```text
/ops observe fixture soak
```

该命令运行一个隔离的真实 Fixture loop，不复用刚才手动演示的状态。它以 72 个逻辑小时推进：第 20—23 小时暂停、第 30 小时重启 state/registry/scheduler，第 12、36、43 小时为 HEALTHY，第 14、38 小时为 UNKNOWN。CLI 会打印这次 snapshot 目录；其中除了四件套，还会有 `accelerated-soak-report.json`。一次选择或拖入同目录五份文件，Console 会校验报告页脚、绑定的 snapshotId 与时间线数量；五张文件卡会显示已校验，并显示“72h · 加速时间 · 非自然墙钟”。

当前 CLI Fixture profile 的机器可读演练结果为：请求/启动 `68/68`，完成/合并/失败 `22/46/0`，时间线 `68`，最终 `1` 个 ACTIVE Incident，Provider `0/0`。这是逻辑时间的受控 Fixture 证据，不能说成自然墙钟连续运行 72 小时。

### 非交互一键证据复现

交互 CLI 会拒绝非 TTY 输入；用于 CI 或留档时，使用同一 Fixture 组件提供的非交互入口，而不是绕过 CLI 的终端约束：

```powershell
mvn -q -pl clawkit-cli -am package -DskipTests
java -cp clawkit-cli/target/clawkit-cli-0.1.0-shaded.jar com.clawkit.ops.loop.autonomy.FixtureAutonomyEvidenceMain <output-directory>
```

该入口依次生成 A0 72 **逻辑**小时 soak 快照、对应的 A3 Shadow policy/decision、一个新鲜 APP_DOWN A0 snapshot 与对应 A3 decision，以及 A3 100 例合约报告。soak 的最后一条 ACTIVE 观测已过 5 分钟窗口，因此其 Shadow 预期为 `ASK_REQUIRED / EVIDENCE_STALE`、`sideEffects=0`，用于证明旧证据不会升级；新鲜 snapshot 的 Shadow 预期为 `ELIGIBLE_SHADOW`、`sideEffects=0`，只证明它会留下一个“可进入 A2 审批”的反事实候选，绝不派发修复。两种结果共同展示门禁，而不是自动修复成功。

将 soak A0 snapshot 中的五份文件、`a3-evaluation/a3-fixture-evaluation-report.json`，以及 `a3-shadow/<snapshot-id>/policies/` 与 `decisions/` 各一份文件同时导入 Console。Console 会重算所有 hash，拒绝 decision 与 manifest 不匹配的组合；实际产物导入通过后，应显示 72h A0 报告、100 例 A3 矩阵、“保持人工审批”与“快照观察已过期”的安全刹车。要展示正例，则改选 `a0-fresh` 的四份 snapshot 文件与 `a3-fresh-shadow/<snapshot-id>/` 的 policy/decision；页面应显示“本会进入 A2 审批（未执行）”和 `side effects 0`，而不是修复成功。

## 自动化证据与范围

| 结论 | 证据 | 不能推导出的结论 |
| --- | --- | --- |
| 同类异常会收敛 | Fixture 测试连续 100 次 APP_DOWN 得到 1 个 ACTIVE Incident、99 次合并；重启后仍合并且产生新的 UUID 运行引用 | 真实告警平台已接入 |
| A0 无模型与写能力 | 固定 Provider 预算 `0/0`；Fixture 代码扫描没有远程会话、Provider 或修复依赖 | 真实生产环境已安全运行 |
| 状态可恢复 | 加速 72 逻辑小时 Fixture soak 覆盖暂停、恢复、波动与重启后的 Registry 保留；当前 CLI profile 为 68 启动、22 完成、46 合并、0 失败 | 已连续运行 72 个自然小时 |
| 展示可回放 | 停止并排空后生成唯一证据快照；manifest 绑定 state、registry、timeline 的精确字节，严格 reader 与离线 Console 再校验 Fixture A0 语义 | 已具备不可抵赖数字签名、远程 Web Dashboard 或多租户平台 |
| A3 反事实记录 | 已校验 Fixture snapshot 生成不可变 policy/decision，稳定 ID 重放同一结论；可追加绑定 decision 的人工反事实选择，均为 `sideEffectCalls=0` | Shadow 样本已完成评测、可替代 A2 审批或具备自动修复能力 |
| A3 合约矩阵 | 固定 100 个合成 Fixture 案例：20 eligible、47 ask、30 rejected、3 expired，报告与离线 Console 均校验 `sideEffectCalls=0` | 真实故障分布、人工采纳率、线上写入安全或 A4 准入 |

## 简历可用表述

建议替换为一条有边界的数据链路，而不是声称已经上线自动运维：

> 构建 Fixture-only 分层自治 Observe-only Loop：持久化自动观测、Incident 指纹去重、暂停/恢复、预算与健康恢复状态；固定 `fixture-*` 目标、Provider 预算为 0，修复路径保持 ASK。100 次重复异常收敛为 1 个 ACTIVE Incident（99 次合并），停止并排空后生成绑定 state、registry、timeline 及白名单观测事实的证据快照，由离线 Console 校验并回放；72 逻辑小时加速 Fixture 演练输出可校验报告，当前 CLI profile 为 68 启动、22 完成、46 合并、0 失败。

面试中应主动补充：100 次与 72 小时均为受控 Fixture 测试，不是线上告警样本或自然运行时长；当前未接入真实服务器 Cron、外部 Probe、通知 Outbox、Provider 诊断或真实 Incident Delivery。
