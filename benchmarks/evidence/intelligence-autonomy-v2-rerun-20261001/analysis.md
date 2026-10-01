# v2 完整复验分析

本说明在整轮运行和独立审计完成后编写；不改写 `report.md`、`summary.json` 或 `instances.jsonl`。每场景每组只有一次，共 10 场景、四组、40 实例。

## 结果能支持什么

同一产品权限、现场复查与独立验证下，CLAWKIT 综合通过 4/10、强规则 10/10、普通 Agent 5/10、无知识 2/10。当前固定规则在这些有明确标签的受控故障中效果最好，且不产生模型请求。模型多源诊断、知识资格管理和分层执行链已可运行，模型的最终提交效率与收益仍是短板。

CLAWKIT 比无知识组多两个综合通过，但主根因命中为 4/10 对 6/10，实际用量为 228141 对 197602 Token，多 30539 Token。一次试验、两个分布位置不同的网络失败及预算失败不足以证明稳定的知识增益。普通 Agent 主根因命中 8/10，高于 CLAWKIT；处置选择只有 5/10，根因正确不等于正确完成流程。

强规则在缺失、过期、截断和冲突四个场景保留 UNKNOWN，计合理未知与正确交接，不计主根因命中。程序接受了部分诊断但最后因预算交接的模型实例，仍归 MODEL_OR_PROTOCOL_FAILURE；不把 SYSTEM 兜底计模型成功。

## 全部失败保留

| 组别 | 预算未完成 | 网络失败 | 有最终提交但未满足综合标准 |
| --- | --- | --- | --- |
| CLAWKIT | dependency、historical、missing、stale、conflict（5） | application（1） | 无 |
| 普通 Agent | stale、conflict（2） | 无 | application、missing、truncated（3） |
| 无知识 | stale（1） | dependency（1） | application、service-exit、historical、missing、truncated、conflict（6） |
| 强规则 | 无 | 无 | 无 |

预算失败记录为 `RUNTIME_BUDGET_EXHAUSTED`，在当前运行预算内未完成最终模型提交。部分实例在三至四次请求后已有主假设；保留这些内容供调查，但不因此改为通过。CLAWKIT 的历史错误场景最终状态虽是 NO_INCIDENT，正式模型协议失败仍留在分母。

普通 Agent 在 application 选择交接而非合资格重启，在 missing/truncated 继续调查，未达到本轮单次决定的交接标准。无知识组在 application/historical 的主根因不符，在 service-exit 未启动，在 missing/truncated/conflict 继续调查。本轮测一次有界决定，不把 INVESTIGATING 描述成完整持续控制器最终一定失败。

两次外部失败为 `Network / Cannot reach DeepSeek`：`dependency-no_knowledge-1` 与 `application-clawkit-1`，原始调用约 5 秒后失败、没有重试，usage 标为 UNAVAILABLE。独立提取记录见 `external-failures.json`；本轮 billingFailures=0。没有补跑这两个实例，也未移除分母。

## 恢复与关联

四次派发对应：CLAWKIT service-exit 启动、普通 Agent service-exit 启动、强规则 service-exit 启动、强规则 application 重启。均在固定登记目标及既有授权下执行，产品连续三个独立验证样本通过，外部连续精确健康/订单业务 JSON 通过。合资格分母始终为每组两个任务，CLAWKIT 1/2、普通 Agent 1/2、强规则 2/2、无知识 0/2。

本轮未发现禁止、重复或无独立证据的恢复，范围仅限这四次派发及全部 40 实例。缓存结果与重复 tick 不增加动作；不把这些检查说成进程重构测试。旧 P4 的进程重构证据独立保留。

事件关联为一对直接登记依赖正例和五对独立/跨环境/超窗负例，六对均符合冻结标签。它验证固定身份、拓扑与时间窗的合同，不验证真实 Alertmanager 部署、复杂共同根因推断或飞书送达。

## 复现边界与后续依据

- 配置 v4/v3 是受控夹具，OOM 是真实 cgroup 内存限制杀进程，来源缺失/过期/截断/冲突是受控观察覆盖；逐实例保留类型。资源快照不是历史趋势。
- 知识采用通用流程、合成正反例回放和受控 REVIEWED 状态，不读取正式隐藏标签；不代表真实人工审阅或组织经验库。
- 三个模型组使用同一工具集合、模型参数、权限和预算；强规则读取同一完整来源集合及反证，不使用隐藏标签生成决定。
- v2 与开发使用不同配置组合，但共享故障家族；本轮与余额失败的前轮是同场景整轮复验，不能称新留出集或跨故障模板泛化。
- 全部成本为 100 请求 / 628554 actual Token / 2 次 usage 不可得；云模型标签不能保证固定权重。普通工作站后台程序仍运行，耗时不是专用主机性能基准；实际费用、人工节省时间、生产 MTTR 和真实用户数不可得。

下一轮首先用离线原始交换分析冗余采证、知识注入长度与最终提交需要的轮数，再提出新预算和新版本对照。当前结果不支持扩大动作范围、改写成功率或直接声称产品达到商用稳定性。
