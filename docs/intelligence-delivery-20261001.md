# 诊断、处置知识与事件协同：阶段验收

2026-10-01，按 [实施合同第 9 节](layered-autonomy-implementation-plan.md#9-下一轮诊断处置知识与事件协同)完成 E0—E4 的本轮交付。完成指实现、协议与负例验证、完整对照记录和可安装交付；模型效果不以“实现完成”代替。逐项当前状态见 [TODO](../TODO.md)。

| 阶段 / 需求 | 已核对的交付与退出条件 | 证据 |
| --- | --- | --- |
| E0 | CI 夹具竞争与 Windows 身份问题修复；坏响应不执行，失败 usage 保留，预算独立，终止决定独占批次 | `tmp/intelligence-e4-v2-clean-verify-20261001.log`：14 模块 SUCCESS，177 suites / 1542 tests / 0 failures / 0 errors / 6 skipped；`tmp/intelligence-e4-v2-runtime-compare-20261001.log`：UNCHANGED / 0 degraded；原混合批次失败仍在 v1 |
| E1 / R9—R10 | 统一来源/时间/质量/引用；实际产品入口可调查依赖、配置、OOM、历史错误；支持、反证、缺口与替代解释可查；身份漂移、截断与预算保守停止 | `AutonomyDiagnosisProductTest`、`DiagnosticEvidenceTest`；四条实际模型/容器开发链在 `tmp/intelligence-e1-live-20261001-05/results.json`，13 请求 / 80906 Token，原先失败各自保留；真实监控平台未部署 |
| E2 / R11—R12 | 草稿、人工确认、不可覆盖版本、范围过滤与本地检索、正负例回放及独立审阅、撤销与执行前复查；知识不扩权 | `ManagedKnowledgeStoreTest` 与 `tmp/intelligence-e2-product-tests-20261001-02.log`；持续产品入口和 CLI 导入/回放/审阅/检索/撤销检查通过；正式有无知识同合同对照见新报告 |
| E3 / R13—R14 首版 | 一个默认关闭的本机告警来源；认证/有界解析/固定身份、重复/乱序、可撤销依赖关联；CLI Inbox 与 Outbox 沿用独立事件与授权 | `tmp/intelligence-e3-product-tests-20261001-01.log`；同消息重复不增加模型或动作、重开无新增调用；六对受控关系独立归档；真实飞书卡片/回调属于合同后续切片 |
| E4 | 事前冻结四组 / 40 实例，强规则读取同来源；分别报告根因、合理未知、处置、恢复、关联及全部成本；原始错误不覆盖 | [完整 v2 复验](../benchmarks/evidence/intelligence-autonomy-v2-rerun-20261001/README.md)及独立 audit；2245 源码输入匹配、运行中不变、清理零残余；v1 与余额失败前轮保留 |
| 安装交付 | 解压包可运行，版本/帮助/默认状态/文件哈希通过；真实入口完成策略与人工两次修复、审批回执、暂停/恢复/停止与独立外部业务验证 | [产品包独立验收](../benchmarks/evidence/intelligence-package-20261001/README.md)：6 请求 / 36802 Token，零未知 usage、两项独立验证、通知关闭、清理零残余；JAR SHA256 `07a5b222a218f44ee3f00ce169033258e201cefa02b370511969944879a1322e` |

## 测量结论

CLAWKIT 综合 4/10、强规则 10/10、普通 Agent 5/10、无知识 2/10；CLAWKIT 合资格恢复 1/2。40 实例的四次派发均通过授权与独立恢复证据核对，未发现禁止或重复动作。全轮 100 请求 / 628554 actual Token，两个网络失败保留在分母，费用不可得。

CLAWKIT 五次预算未完成及一次网络失败明确解释；普通 Agent 和无知识组的错误处置也完整记录。数据没有证明 Agent 优于规则或稳定的知识增益。当前依据可以讲多源证据诊断、审核知识反馈和事件协同的产品链；不能写生产稳定性、效率提升或线上恢复率。

## 范围与后续

已验证写操作仍是隔离本地 Linux Docker 中登记无状态容器的启动/重启，模型不生成 Shell 或目标权限。真实远程自主写入、实际 Alertmanager 部署、飞书送达与卡片回调、第二个告警来源、公网发布未计作本轮验收。新一轮效果优化应先分析保留的原始交换，再另开版本/冻结运行。

源码、完整原始交换与源码归档保存在本地 `tmp/`，公开脱敏结果和失败在 `benchmarks/evidence/`；安装包附带该证据目录、使用说明、配置示例和 SHA256。最终 ZIP 的实际生成回执在输出目录中保存，未宣称已创建 GitHub Release。
