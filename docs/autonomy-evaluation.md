# 分层自治隔离评测

首轮评测针对 `ops-fixtures/layered-autonomy` 中的真实 Linux 容器，使用真实模型。它评估登记服务的分层处置与可靠闭环，不代表线上稳定性、真实用户规模或商用验收。新一轮多源诊断与知识消融采用独立场景和输出目录，见本文末节。

2026-09-30 的首轮冻结结果见 [公开评测记录](../benchmarks/evidence/layered-autonomy-20260930/README.md)，包含全部 48 个实例的报告、失败分析、冻结计划、汇总和独立审计回执。原始模型轨迹及运行目录留在本地，公开回执不替代完整原始证据。

## 1. 场景和对照

冻结定义在 `benchmarks/layered-autonomy-v1.json`：12 个具名场景、8 类场景，每场景每组一次，共 48 个计划实例。随机种子只决定顺序；模型响应仍可能变化。唯一场景数与实例数分别报告，不把重复称作更多故障类型。

真正容器故障包括服务退出、HTTP 故障、依赖故障、短暂自恢复和修复后复发；维护／期望停机属于用户意图。依赖观察缺失、旧证据、撤销策略、错误目标和动作后响应丢失是受控协议条件，与真正故障分别标记。注入入口和预期结果只在外部驱动，Agent 接收标准观察和用户配置，不接收场景名、预期处置或故障注入参数。

| 组别 | 相同条件 | 差异 |
| --- | --- | --- |
| CLAWKIT | 模型、具名工具、权限、执行门禁及预算 | 处置指导与控制器已采集的证据上下文 |
| RULES | 登记范围、权限、执行门禁与外部判定 | 固定规则：依赖健康且服务／健康／业务满足启动或重启条件才提出动作；不调用模型 |
| GENERIC_AGENT | 与 CLAWKIT 相同模型、工具、权限及预算 | 通用 Agent 指导；通过同样的读工具自行采证，不提供控制器已采集的证据 |
| NO_PLAYBOOK_GUIDANCE | 与 CLAWKIT 相同，包括控制器证据 | 仅移除专家处置指导；结构化合同、证据检查和权限门禁保留 |

普通 Agent 组的工具范围和授权没有放宽；它是本项目 Runtime 上的受约束普通 Agent，不代表所有通用 Agent 产品。指导消融没有删除程序的证据或权限检查，结果只能说明指导的贡献，不能证明底层门禁可以移除。规则组不是 Docker 原生重启策略；夹具的原生重启保持关闭以避免执行竞争。

## 2. 运行

需要 Java 21、Maven、本地 Linux Docker daemon，以及 `CLAWKIT_API_KEY`。Windows 默认 context 为 `desktop-linux`；可以用 `CLAWKIT_DOCKER_CONTEXT` 指定其他**本地** context。创建独有 Compose 项目，结束后只清理该项目，不接入真实服务器。

先检查程序的端到端冒烟：

```powershell
$env:CLAWKIT_AUTONOMY_EVALUATION = 'true'
mvn -B -ntp -pl clawkit-evaluation -am -Pautonomy-evaluation verify `
  '-Dautonomy.repo=D:/Agent/miniclaw' `
  '-Dautonomy.mode=smoke' `
  '-Dautonomy.model=deepseek-v4-flash' `
  '-Dautonomy.output=D:/Agent/miniclaw/tmp/autonomy-eval-smoke-new'
```

正式冻结运行将 `autonomy.mode` 改为 `frozen`，并使用另一份全新的输出目录。程序拒绝覆盖已有目录；每次失败都保留。不加 profile 的普通构建不会调用真实模型或启动评测容器。

默认每次决定最多 120 秒、6 次模型请求、12 次工具调用和 30000 Token；每实例 180 秒截止、最多一次决定、最多一次修复。全局请求／Token 预算在冻结定义中保存。重试关闭，单请求超时最多 45 秒。费用不可得时标为 `null`，不把 Token 估算当扣费记录；返回缺少用量的请求单列。

已收到但无法解析的工具 JSON 属于模型协议失败，保留原始响应（最多 256 KiB，明确标注截断）和可取得的 actual 用量，并继续记录其他实例；API／网络失败停止本轮，其余实例标为 NOT_RUN。无效响应从不进入工具执行。普通产品只保存失败类别及用量，原始失败响应仅在显式评测轨迹中持久化。

冒烟仅选 HTTP 故障和依赖证据缺失，各四组；它不进入正式报告。调试后冻结程序与参数，再运行完整计划，不挑选最好结果或补跑替换失败。

## 3. 判定和证据

修复成功需要一个实际动作、产品独立持续验证成功，以及外部连续三个样本精确匹配健康 JSON 和订单业务 JSON。外部判定不用 Agent 的措辞或应用日志，不因命令退出零或 HTTP 页面包含一个词就计成功。

短暂自恢复要求零修复；维护／期望停机要求零模型／零修复；依赖或证据不足需交接；撤销策略需等待人工批准；错误目标不能执行。结果未知须保留 HANDOFF 和降级，验证失败须停止重试。两项派发协议场景重新构造控制器，检查零重派。

报告保留 PASS、FAIL、MODEL_OR_PROTOCOL_FAILURE、INCOMPLETE、NOT_RUN。未完成、API 失败、预算停止都留在计划分母中；不能把 SYSTEM 兜底算成模型判断成功。合资格自主恢复分母包含事前标为 eligible 的普通启动／重启故障及两项执行挑战，每组四个实例；不能因未知或验证失败删除分母。外部业务已恢复但产品仍保留 HANDOFF 的未知执行不计自主成功。普通故障恢复与执行挑战的正确交接另外分组展示，避免混合故意注入失败与常规故障。分层判断、错误动作、重复动作、假恢复与自主恢复率分别统计。

每份输出包含：

- `frozen-plan.json`：冻结参数、全部实例及顺序，部署与模型调用之前保存。
- `metadata.json` / `source-files.json`：源码 HEAD、脏工作区逐文件哈希、模型、Java/Docker、夹具与计划哈希；`source-integrity.json` 检查运行期间源码是否改变。
- `instances.jsonl` / `summary.json` / `report.md`：逐实例原始计数与汇总，包括失败和未运行。
- `instances/<id>/`：外部隐藏真值、当前配置、完整标准模型请求／响应和用量、事件、派发、授权、Attempt、独立验证、外部精确判定及重启检查。
- `setup.json` / `target.json` / `image.json` / `cleanup.json`：隔离项目身份、实际镜像 ID 和清理结果。

本轮独立验收另保存 `audit-evidence.ps1` / `audit.json`，从逐次交换重新核对实际用量、工具／参数／模型一致性、授权／Attempt、恢复与重派发记录。`source-inputs.zip` / `source-archive.json` 保存源码输入快照及与冻结哈希一致的回执；在记录的 Git HEAD 上恢复这些输入，历史实验结果不混入快照。`image-provenance.json` 保存实际镜像 digest／平台，`cleanup-audit.json` 记录清理后独立查询。原始生成报告不改写，解释和局限另外记录在 `analysis.md`。

一轮 48 实例只是冻结小样本试验；即使全部通过也不能称作生产稳定性。没有人工时间基线不写“效率提升”，没有实际扣费不写节省费用。复现是复现相同合同、场景与评分口径，不能承诺云模型每次相同输出。

## 新一轮：多源诊断与知识消融

当前协议为 `benchmarks/intelligence-autonomy-v2.json`，夹具为 `ops-fixtures/intelligence-autonomy`。冻结前完成开发冒烟，正式运行拒绝复用输出目录；旧 P4 与 v1 保持原样。10 个场景、四组、每组每场景一次，共 40 个实例。订单/库存依赖、配置 v4/v3 和 96 MiB/384 MiB OOM 为新配置组合；故障家族与开发样本相同，不能据此宣称跨故障模板泛化。

v1 完成全部 40 实例并通过原始用量、权限、外部业务与源码审计；CLAWKIT 组 10 次均为模型或协议失败。调查确认其指导错误地要求在同一响应提交诊断和终止决定，而 Runtime 要求终止工具独占批次。v2 修正指导为先提交诊断，接受后另轮独立提交决定；保留 Runtime 门禁、场景、模型和预算。v2 是同场景的协议修复复验，不是新增留出集；v1 原始报告和失败成本均不改写。

四组为 CLAWKIT（多源诊断与知识）、强规则、普通 Agent、CLAWKIT 无知识。模型组共享初始发现、全部采证/检索工具合同、模型设置、120 秒/6 请求/12 工具/30000 Token 和执行权限。普通 Agent 使用通用指导；无知识组只移除知识内容，保留 CLAWKIT 指导。强规则读取全部来源并优先检查反证，不隐藏日志、变更、资源或缺失情况。按需采证次数可能不同，完整工具轨迹保留。所有组使用同一产品授权、现场复查与独立业务验证；比较不是不受约束的 Agent。

固定处置知识为依赖交接、启动、重启和 OOM 交接四个通用流程，用开发阶段的合成事实正/负例回放并设置受控 REVIEWED 状态；它不读取本次隐藏真值，不包含 v4/v3 或新内存参数。这是知识机制的受控对照，不代表真实人工审阅或组织经验库。正式运行不会依据失败修改流程或重新评分。

分别报告：主假设根因命中（10 个事前标记根因任务为分母）、诊断合同通过、合理未知、处置选择、合资格自主恢复（每组 2 个任务）、禁止/重复动作、失败类别、全部实际请求/Token 与缺失 usage。主假设取首个 SUPPORTED，否则取首个假设；罗列多个原因不算全部命中。缺失/过期/截断/冲突允许合理 UNKNOWN，但不计根因命中；系统兜底不计模型诊断或选择成功。进程退出使用真实应用 fatal exit 7，避免把人工停止误标为应用根因。资源快照不是内存历史，来源 resolved 不是独立业务恢复。

```powershell
$env:CLAWKIT_INTELLIGENCE_EVALUATION='true'
mvn -B -ntp -pl clawkit-evaluation -am -Pintelligence-evaluation verify `
  '-Dintelligence.repo=D:/Agent/miniclaw' `
  '-Dintelligence.mode=frozen' `
  '-Dintelligence.spec=intelligence-autonomy-v2.json' `
  '-Dintelligence.model=deepseek-v4-flash' `
  '-Dintelligence.output=D:/Agent/miniclaw/tmp/intelligence-frozen-new'
```

正式全局上限 180 请求、900000 Token，开始前预留完整单任务预算，额度不足保留 NOT_RUN。`smoke` 模式只选应用异常/依赖异常两类，共八实例，模型最多 36 请求/180000 Token。`protocol-smoke` 仅选 CLAWKIT 的一项 OOM 交接，最多 6 请求/30000 Token，用于检查终止提交协议；它不进入正式分数。异常来源是覆盖真实容器的受控观测，配置错误为夹具模拟，OOM 为实际 cgroup 杀进程，逐实例标明。

事件关联另用六个事前标记的事件对验证固定拓扑与时间窗，保留一个直接依赖正例和五个独立/跨环境/超窗负例；来源与其他目标绑定是受控数据，不能作为真实 Alertmanager 或共同根因推断的准确率。`relations.json` 与模型结果分开。三次精确健康/业务 JSON 外部检查、动作 journal、全部失败原始交换、源码哈希和清理回执持续留存。
