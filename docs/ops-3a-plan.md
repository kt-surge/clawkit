# OPS-3A：Observe-only 持续发现实施计划

> 状态：A-D 已完成并验收；PRODUCT-3 的 7 天 dogfood 继续并行。
> 边界：仅可丢弃 Fixture、仅只读 Discovery/Diagnosis/Report、所有修复保持 ASK。

## 目标

把手动调查升级为受控的持续只读观察：周期性发现异常、创建或合并 Incident、输出报告。它不能执行 `restart_service`，不能调用 `opsfix`，也不能接入真实 `test-server` 的定时任务。

## 第一个闭环：持续发现与 Incident 去重

输入是 Fixture 的只读 Discovery 结果；输出是一个持久化 Incident Registry 决定：创建新 Incident、合并到现有 ACTIVE Incident、因冷却跳过，或因暂停/预算停止。

Fingerprint 至少由以下稳定字段构成：

```text
targetId + serviceId + discoveryProfile + diagnosisCategory + policyVersion
```

不得使用原始日志、随机 runId、时间戳或模型自然语言作为 fingerprint 输入。

## 分阶段交付

### A. Registry 与去重

- 新增持久化 Registry，保存 fingerprint、Incident、状态、首次/最后观察时间、次数和最近 run；
- 同 fingerprint 的 ACTIVE Incident 合并，不创建第二条 Incident；
- 已关闭 Incident 在冷却窗口内不重复通知；
- 同一 target 的 Discovery 互斥；
- 测试并发触发、进程重启后的恢复和持久化损坏 fail-closed。

验收：同一 Fixture 异常连续触发 100 次，只保留一条 ACTIVE Incident；同 target 并发 Discovery 为 0。

### B. Scheduler、暂停与取消

- 使用可注入 Clock/Scheduler，不依赖系统 Cron 作为第一版测试基础；
- 支持 target 级 pause/resume、全局 pause 和取消尚未开始的任务；
- pause 后不得新建 run、Incident 或 Provider 调用；
- 运行中只读任务可自然收尾或按 deadline 取消。

验收：暂停后 100 次触发均不产生新任务；恢复后仅产生一次符合去重规则的 Discovery。

### C. 预算与恢复

- 分离 Discovery 调用预算与 Provider 预算；
- 预算耗尽后停止对应调用并记录原因，不把失败伪装成健康；
- 重启恢复 pending/active Registry，不重复通知，不派发任何写 Attempt；
- 报告最近执行、跳过原因、合并数、预算状态。

验收：预算耗尽后的 Provider 调用数为 0；重启后重复通知数为 0；写操作调用数始终为 0。

### D. 72 小时 Fixture soak

- 使用加速时钟模拟并保留真实时间戳，覆盖稳定异常、恢复、抖动、暂停、重启、预算耗尽；
- 输出原始 requested/completed/skipped/merged/failed 数量，不用百分比掩盖样本量；
- 所有发现均可追溯到 Incident 与只读证据。

验收：等价 72 小时连续运行，无并发同目标、无重复 ACTIVE Incident、无重复通知、无写操作。

## 明确不做

- 不调用 `FixSession`、`restart_service`、`opsfix` 或任何 shell/容器写操作；
- 不接真实服务器 Cron、外部告警或飞书通知；
- 不进入 Shadow、AUTO、Playbook memory 或第二个修复动作；
- 不以 PRODUCT-3 尚未完成的 dogfood 数据替代 OPS-3A 的 Fixture 证据。

## 每阶段共同门禁

- 默认 fail-closed；Registry 损坏、目标不合法、预算不明或配置不完整时不启动；
- target 始终可见，模型不参与去重与授权判断；
- 所有测试必须断言写操作调用次数为 0；
- `mvn test`、`git diff --check` 通过后才推进下一阶段。

## 收尾实现与验收记录（2026-08-05）

### 已实现的闭环

- `IncidentCooldownPolicy`：关闭后的同一 fingerprint 在冷却窗口内返回
  `COOLDOWN_SKIPPED`。它保留原 Incident，不创建新 Incident，也不进入 Provider。
- `ObservationToIncidentBridge`：只有确定性的 `HEALTHY` 会关闭对应 ACTIVE
  Incident 并记录 `HEALTHY_RECOVERED`；`UNKNOWN` 仍绝不关闭 Incident。
- `ObservationAutomationCoordinator`：把冷却跳过作为一次已完成的只读观察记录，
  保留证据，但不触发 Provider；暂停、预算和状态均继续走持久化 fail-closed 门禁。
- `AutomationSoakTest`：使用 `MutableTestClock`、实际 `AutomationStateStore` 和
  `IncidentRegistry` 文件，覆盖稳定异常、恢复、抖动、暂停、重启与预算耗尽。

### 72 小时 Fixture soak 原始结果

加速时钟从 `2026-08-05T00:00:00Z` 前进 72 个逻辑小时，每小时尝试一次调度；第
20-23 小时 target 暂停，第 30 小时关闭并重开 State/Registry/Scheduler。

| 项目 | 结果 |
| --- | ---: |
| requested | 68 |
| started / 实际 Fixture Discovery | 35 |
| skipped（暂停不入计；此处均为预算拒绝） | 33 |
| completed | 13 |
| merged | 22 |
| failed | 0 |
| 最大同 target 并发 | 1 |
| Provider 调用 | 0 |
| Fix/opsfix/restart/远端写调用 | 0 |
| 最终 ACTIVE Incident | 1 |

每个实际 Discovery 产生两条 `run://fixture-*` 只读证据引用（共 70 条）；恢复后
的冷却状态在 Registry 重启后仍生效。该 soak 是可重复的加速 Fixture 证据，不是
真实服务器的 72 个自然小时运行，也不授权把 `test-server` 加入 scheduler。

验证命令：

```powershell
mvn -q -pl extensions/clawkit-ops-loop -am test
git diff --check
```

本次结果：`clawkit-ops-loop` 262 tests、0 failures、0 errors、0 skipped；
automation 主代码的写能力 import/调用静态扫描为空。

### 2026-09-20：CLI Fixture profile 的机器可读演练证据

上表是历史的 `AutomationSoakTest` 结果：它在每 6 个逻辑小时内最多允许 3 次 Discovery，
所以有 35 次实际启动和 33 次预算拒绝。它不能与下面的 CLI profile 混写。

新增 `/ops observe fixture soak` 会启动隔离的真实 `FixtureObserveOnlyLoop`，使用每小时窗口
100 次 Discovery、Provider 固定 0 的当前 CLI Fixture profile。它生成 state、registry、timeline
（含与引用一一对应的白名单事实）的 manifest 快照，以及快照目录内带 SHA-256 页脚的 `accelerated-soak-report.json`；报告再绑定
snapshotId、时间线数量、计数关系与安全不变量。2026-09-20 从真实 CLI 入口生成并由离线 Console
回放的样本为 `fixture-snapshot-386d7067-818d-44fb-86af-f1815328087d`。

| 项目 | 当前 CLI Fixture profile |
| --- | ---: |
| requested / started | 68 / 68 |
| completed / merged / failed | 22 / 46 / 0 |
| skipped | 0 |
| timeline events | 68 |
| Registry entries / ACTIVE Incident | 1 / 1 |
| Provider | 0 / 0 |
| 远程会话、修复、写操作 | 0 / 未接入 |

第 12、36、43 小时输入 HEALTHY，第 14、38 小时输入 UNKNOWN；UNKNOWN 会保留一条
`COLLECTION_FAILED` 的 Fixture 证据而不会关闭 ACTIVE Incident。第 20—23 小时暂停，第 30
小时重开 state/registry/scheduler。该报告声明 `ACCELERATED_LOGICAL_TIME`，页面明确显示为
“72h · 加速时间 · 非自然墙钟”；它是可重复的受控演练，不是自然运行 72 小时，也不是生产告警数据。

验证链路：

```powershell
/ops observe fixture soak
node ops-console/test/console-contract.test.js <这次 snapshot 目录>
```

当前回归：`clawkit-ops-loop` 286 tests、0 failures、0 errors；`clawkit-cli` 289 tests、0 failures、0 errors；Console 的合成契约与上述真实五文件 snapshot 回放均通过。

### 反方审查结论

- 不以真实 `test-server` 验证调度、暂停、预算或恢复：它仅用于按既有契约的手动
  只读连通性检查，绝不接 Cron、Provider 或写能力。
- 不把网络失败当成恢复：`UNKNOWN` 保持 ACTIVE；只有完整且确定性的 HEALTHY
  证据才关闭事件。
- 冷却不丢弃观察证据：仍完成 Discovery 并落状态，但抑制新 Incident/Provider。
- Registry 在文件损坏或锁失败时 fail-closed；其当前整文件强制落盘实现不承诺
  进程在写入中断时自动恢复，应由运维保留损坏文件并人工处置，而不是继续调度。
