# 多源诊断 v2：额度恢复后的完整复验

2026-10-01，在供应商额度恢复后，使用同一 v2 协议、场景、顺序、工具和预算重新运行全部 40 实例。运行源码为 `f62125d3ef1f648dfe50d0d533cf4d3cc18a4562`。这是整轮同场景复验，不是新增留出集，也没有替换历史失败。

| 组别 | 综合通过 / 10 | 主根因命中 / 10 | 诊断合同通过 / 10 | 处置选择通过 / 10 | 合资格自主恢复 / 2 | 实际请求 | actual Token |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| CLAWKIT | 4 | 4 | 4 | 4 | 1 | 36 | 228141 |
| 强规则 | 10 | 6 | 10 | 10 | 2 | 0 | 0 |
| 普通 Agent | 5 | 8 | 8 | 5 | 1 | 32 | 202811 |
| 无知识 | 2 | 6 | 6 | 3 | 0 | 32 | 197602 |

**本轮没有证明 CLAWKIT 优于规则，也不足以证明稳定的知识收益。** 强规则四个合理 UNKNOWN 单列，不计根因命中。CLAWKIT 与无知识组的综合差异不能代替多轮因果证据；普通 Agent 的主根因命中更高。

全部 40/40 记录：21 PASS、9 FAIL、10 MODEL_OR_PROTOCOL_FAILURE，0 未运行。整轮 100 次实际请求、628554 actual Token；两次网络失败 usage 不可得，费用为 null。本轮没有余额不足错误。网络失败仍在分母，未知用量不当作免费调用。

独立审计通过：四次实际派发均有授权、独立连续恢复样本和外部精确业务判定；未发现禁止或重复动作。六对受控关联全部符合事前标签，不能作为真实告警部署的准确率。源码 2245 份输入的快照哈希一致，运行期间未变化，清理后零残余容器。

## 记录导航

- [分析与局限](analysis.md)：逐组失败、成本、恢复对象及适用边界。
- [原始生成报告](report.md)、[汇总](summary.json)、[逐实例结果](instances.jsonl)、[冻结计划](frozen-plan.json)。
- [独立审计](audit.json)、[关联](relations.json)、[外部失败说明](external-failures.json)、[环境说明](environment-notes.json)。
- [复现与文件哈希](provenance.json)、[镜像身份](image-provenance.json)、[源码完整性](source-integrity.json)、[清理审计](cleanup-audit.json)。
- [v1 历史完整结果](../intelligence-autonomy-v1-20261001/README.md)、[v2 首轮余额失败记录](../intelligence-autonomy-v2-20261001/README.md)、[安装包实际入口验收](../intelligence-package-20261001/README.md)。

原始模型交换、完整 journal 和 `source-inputs.zip` 保留在本地 `tmp/intelligence-e4-v2-frozen-20261001-02/`；公开文件未发布原始模型轨迹。源码归档 SHA256 为 `9f441cb4c010844f6da385d5c173967645947d180730fbf2f1bfc23c314827e8`。公开 JSON 仅规范换行，原始与公开哈希见 provenance；分析不修改原始评分。

请求模型名 `deepseek-v4-flash`，响应返回 `deepseek-flash`。这两项是运行记录，不证明供应商固定了模型权重版本。模型组统一 temperature=0、4096 输出上限、非流式、原生思考 DISABLED，单任务 120 秒 / 6 请求 / 12 工具 / 30000 Token。
