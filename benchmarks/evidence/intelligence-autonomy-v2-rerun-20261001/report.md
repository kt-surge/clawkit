# 多源诊断与知识消融冻结试验

小样本隔离试验；来源受控负例单列，实际用户效益和生产 MTTR 未测。费用不可得，报告实际请求及 Token。知识 REVIEWED 是独立回放与受控审阅夹具，不表示真实人工审核。

| 实例 | 结果 | 根因 | 处置 | 状态 | 动作 | 请求 | Token |
| --- | --- | --- | --- | --- | --- | --- | --- |
| dependency-no_knowledge-1 | MODEL_OR_PROTOCOL_FAILURE | UNKNOWN / false | ESCALATE / false | HANDOFF | 0 | 1 | 0 |
| service-exit-generic_agent-1 | PASS | APPLICATION_FAILURE / true | PROPOSE_ACTION / true | RECOVERED | 1 | 3 | 17859 |
| historical-clawkit-1 | MODEL_OR_PROTOCOL_FAILURE | SELF_RECOVERY / false | ESCALATE / false | NO_INCIDENT | 0 | 4 | 26616 |
| oom-clawkit-1 | PASS | RESOURCE_EXHAUSTION / true | ESCALATE / true | HANDOFF | 0 | 4 | 26069 |
| configuration-clawkit-1 | PASS | CONFIGURATION_MISMATCH / true | ESCALATE / true | HANDOFF | 0 | 4 | 26460 |
| application-strong_rules-1 | PASS | APPLICATION_FAILURE / true | PROPOSE_ACTION / true | RECOVERED | 1 | 0 | 0 |
| truncated-generic_agent-1 | FAIL | APPLICATION_FAILURE / true | INVESTIGATE / false | INVESTIGATING | 0 | 3 | 17984 |
| historical-generic_agent-1 | PASS | SELF_RECOVERY / true | WAIT / true | NO_INCIDENT | 0 | 3 | 18624 |
| conflict-strong_rules-1 | PASS | UNKNOWN / true | ESCALATE / true | HANDOFF | 0 | 0 | 0 |
| missing-generic_agent-1 | FAIL | APPLICATION_FAILURE / true | INVESTIGATE / false | INVESTIGATING | 0 | 3 | 17799 |
| truncated-strong_rules-1 | PASS | UNKNOWN / true | ESCALATE / true | HANDOFF | 0 | 0 | 0 |
| stale-clawkit-1 | MODEL_OR_PROTOCOL_FAILURE | UNKNOWN / false | ESCALATE / false | HANDOFF | 0 | 3 | 20452 |
| stale-generic_agent-1 | MODEL_OR_PROTOCOL_FAILURE | UNKNOWN / false | ESCALATE / false | HANDOFF | 0 | 4 | 28694 |
| stale-no_knowledge-1 | MODEL_OR_PROTOCOL_FAILURE | UNKNOWN / false | ESCALATE / false | HANDOFF | 0 | 3 | 20530 |
| dependency-strong_rules-1 | PASS | DEPENDENCY_FAILURE / true | ESCALATE / true | HANDOFF | 0 | 0 | 0 |
| oom-generic_agent-1 | PASS | RESOURCE_EXHAUSTION / true | ESCALATE / true | HANDOFF | 0 | 3 | 18292 |
| service-exit-strong_rules-1 | PASS | APPLICATION_FAILURE / true | PROPOSE_ACTION / true | RECOVERED | 1 | 0 | 0 |
| conflict-generic_agent-1 | MODEL_OR_PROTOCOL_FAILURE | RESOURCE_EXHAUSTION / false | ESCALATE / false | HANDOFF | 0 | 4 | 29137 |
| truncated-clawkit-1 | PASS | APPLICATION_FAILURE / true | ESCALATE / true | HANDOFF | 0 | 4 | 26000 |
| application-clawkit-1 | MODEL_OR_PROTOCOL_FAILURE | UNKNOWN / false | ESCALATE / false | HANDOFF | 0 | 1 | 0 |
| dependency-clawkit-1 | MODEL_OR_PROTOCOL_FAILURE | DEPENDENCY_FAILURE / false | ESCALATE / false | HANDOFF | 0 | 4 | 25436 |
| historical-no_knowledge-1 | FAIL | APPLICATION_FAILURE / false | WAIT / true | NO_INCIDENT | 0 | 3 | 18216 |
| configuration-strong_rules-1 | PASS | CONFIGURATION_MISMATCH / true | ESCALATE / true | HANDOFF | 0 | 0 | 0 |
| stale-strong_rules-1 | PASS | UNKNOWN / true | ESCALATE / true | HANDOFF | 0 | 0 | 0 |
| application-no_knowledge-1 | FAIL | CONFIGURATION_MISMATCH / false | ESCALATE / false | HANDOFF | 0 | 4 | 26116 |
| truncated-no_knowledge-1 | FAIL | APPLICATION_FAILURE / true | INVESTIGATE / false | INVESTIGATING | 0 | 3 | 18230 |
| oom-strong_rules-1 | PASS | RESOURCE_EXHAUSTION / true | ESCALATE / true | HANDOFF | 0 | 0 | 0 |
| service-exit-no_knowledge-1 | FAIL | APPLICATION_FAILURE / true | ESCALATE / false | HANDOFF | 0 | 4 | 26151 |
| conflict-clawkit-1 | MODEL_OR_PROTOCOL_FAILURE | UNKNOWN / false | ESCALATE / false | HANDOFF | 0 | 4 | 25522 |
| application-generic_agent-1 | FAIL | APPLICATION_FAILURE / true | ESCALATE / false | HANDOFF | 0 | 3 | 18245 |
| oom-no_knowledge-1 | PASS | RESOURCE_EXHAUSTION / true | ESCALATE / true | HANDOFF | 0 | 3 | 18585 |
| missing-no_knowledge-1 | FAIL | APPLICATION_FAILURE / true | INVESTIGATE / false | INVESTIGATING | 0 | 4 | 24681 |
| dependency-generic_agent-1 | PASS | DEPENDENCY_FAILURE / true | ESCALATE / true | HANDOFF | 0 | 3 | 18090 |
| missing-clawkit-1 | MODEL_OR_PROTOCOL_FAILURE | APPLICATION_FAILURE / false | ESCALATE / false | HANDOFF | 0 | 4 | 25654 |
| conflict-no_knowledge-1 | FAIL | APPLICATION_FAILURE / true | INVESTIGATE / false | INVESTIGATING | 0 | 4 | 26908 |
| missing-strong_rules-1 | PASS | UNKNOWN / true | ESCALATE / true | HANDOFF | 0 | 0 | 0 |
| configuration-no_knowledge-1 | PASS | CONFIGURATION_MISMATCH / true | ESCALATE / true | HANDOFF | 0 | 3 | 18185 |
| service-exit-clawkit-1 | PASS | APPLICATION_FAILURE / true | PROPOSE_ACTION / true | RECOVERED | 1 | 4 | 25932 |
| historical-strong_rules-1 | PASS | SELF_RECOVERY / true | WAIT / true | NO_INCIDENT | 0 | 0 | 0 |
| configuration-generic_agent-1 | PASS | CONFIGURATION_MISMATCH / true | ESCALATE / true | HANDOFF | 0 | 3 | 18087 |

汇总见 summary.json。全部实例含未运行/失败；model-exchanges、冻结输入与隐藏真值保留，外部业务用三样本精确 JSON 判定。关联来自固定来源合同试验，单独见 relations.json，不与模型诊断合并。整体异常：null。
