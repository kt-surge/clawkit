# CM-E4 长任务修复复验（2026-10-05）

> 当前结果（公开规则验收 v2 首轮）：6 实例全部尝试，1/6 完整通过（C1 摘要基线的动态任务）；候选 C3 为 0/2，配置文件 25/25 正确但未收尾，动态文件 16/21 正确。177 次实际请求 / 1442697 total Token，8 个付费失败全部入账。Java 与 Node 复算完成，质量门槛仍未通过；最新证据见文末“公开规则验收 v2：首轮真实复验”。

## 第二轮：输出截断恢复修复回归（历史记录）

同一批2个自建合成任务、3组各1次，共6实例，全部尝试、0完整通过。窗口16384、输出2048、每实例60请求/360000 Token；请求重试0。v1已经被观察，本轮是普通Runtime修复的回归，不提供新留出集泛化结论。

| 实例 | 根状态 | 实际主任务轮 | 压力压缩 | 请求 | total Token | 文件正确数 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| configuration-migration-c1_rolling_summary | COMPLETED | 35 | 2 | 37 | 270933 | 11/25 |
| configuration-migration-c2_frozen_original_context | COMPACT_FAILED | 10 | 0 | 10 | 63768 | 6/25 |
| configuration-migration-c3_candidate_context | COMPLETED | 36 | 2 | 38 | 280539 | 17/25 |
| changing-evidence-continuation-c2_frozen_original_context | COMPACT_FAILED | 29 | 0 | 29 | 180918 | 0/21 |
| changing-evidence-continuation-c3_candidate_context | BUDGET_EXHAUSTED | 44 | 0 | 44 | 322681 | 0/21 |
| changing-evidence-continuation-c1_rolling_summary | BUDGET_EXHAUSTED | 39 | 1 | 40 | 328842 | 12/21 |

共198次实际请求，input 1378947、output 68734、total 1447681；cache hit 1112192/miss 266755是input子项。6个付费失败响应均保留；所有用量可得，原始失败未覆盖。预算停止包含派发前预留判断，不能按实际总额未到360000推断预算器错误。

## 审核与失败定位

Java独立重评分的完整性、结果一致性通过，live-full门禁false。另以Node独立转写公开规则，在读取Gold前重建46个期望输出；公开规则与Gold一致，6实例最终文件评分一致。该人工规则审阅是未盲化开发审阅。

候选配置实例有36次实际主任务响应、2次压力压缩，根状态COMPLETED，但25份文件仅17份正确，prod的8份均有资源/遥测等字段错误；不计整项成功。候选动态实例44次实际主任务响应、预算停止，最终21份文件均未满足最新状态规则。当前证据支持补齐来源/进度导航并减少截断恢复后的工具批量限制，尚不能单独归因于压缩算法。

原始目录：tmp/cm-full-live-20261005-02/；用量/授权结束回执：tmp/cm-api-execution-full-20261005-02.json；Java审计：tmp/cm-full-live-audit-20261005-02.json；公开规则二次审计：tmp/cm-full-public-rule-audit-20261005-02.json。运行期间542份来源未变，原版Context JAR和数据未变。

## 后续

普通Runtime增加单Run、有界的原生文件来源和写入进度导航，参考实际成功工具结果；不注入Gold、不改任务/评分/预算。写入确认仅说明文件副作用，不代表业务正确；未知/部分效果不得记为完成。修复先离线验证并prepare/audit，再按逐轮合同申请新API轮次。本轮授权已结束，剩余额度不复用。CM-E4及主目标保持进行中。


## 原生读取与实际模型送达补充复核

另从封存请求/响应、原生成功事件及环境推进复核六实例的来源链，见tmp/cm-full-source-delivery-20261005-03.json。配置组三组的12份静态必要来源都曾逐字进入付费实际主任务请求；不代表后续压缩仍保留每个精确值。动态组三组的2份静态规则/历史来源亦送达；runtime/validation.json属于环境生成的结构报告，不把它与初始空占位文件混比。

原版动态：环境最后revision 2，原生读取revision 2成功但完整字节未送达实际主任务，根停止于COMPACT_FAILED。候选动态：revision 2曾完整送达4个实际主任务请求；最后写入触发环境进入revision 3，随后预算停止，尚未原生重读revision 3。摘要基线动态：revision 3已原生读取并完整送达，但根预算停止且产物未全部正确。分别区分读取、送达、语义应用和阶段完成，不将前两项当作任务成功。

这项未盲化开发复核检查完整字节可用性与来源归属，不自动判定语义蕴含；公开规则的独立转写与最终文件评分已单独审核。原始评分/失败/封存不变，未新增API调用。新修复候选仍6项NOT_RUN，真实长任务门禁未完成。


## 第三轮：文件导航检查点修复回归

用户直接回复“批准”后运行同一批已观察的2个合成任务、3组各1次，共6实例。全部尝试，1/6通过（摘要基线配置任务），候选两项均未通过，live-full门禁仍为false。既有v1属于修复回归，不能提供泛化效果或生产收益。

| 实例 | 根状态 | 实际主任务轮 | 压力压缩 | 请求 | total Token | 文件正确数 | 完整结果 |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| configuration-migration-c1_rolling_summary | COMPLETED | 23 | 2 | 25 | 211423 | 25/25 | PASS |
| configuration-migration-c2_frozen_original_context | COMPACT_FAILED | 7 | 0 | 7 | 45053 | 8/25 | FAIL |
| configuration-migration-c3_candidate_context | COMPLETED | 28 | 2 | 30 | 253630 | 17/25 | FAIL |
| changing-evidence-continuation-c2_frozen_original_context | COMPACT_FAILED | 19 | 0 | 19 | 117275 | 0/21 | FAIL |
| changing-evidence-continuation-c3_candidate_context | BUDGET_EXHAUSTED | 38 | 0 | 38 | 319531 | 0/21 | FAIL |
| changing-evidence-continuation-c1_rolling_summary | COMPLETED | 38 | 2 | 40 | 315756 | 20/21 | FAIL |

共159次实际请求，input 1193477、output 69191、total 1262668；cache hit 766208/miss 427269是input子项。6个付费失败响应全部保留，用量均可得。Java封存独立复算的完整性和结果一致性通过；Node分别转写公开规则复算46个期望文件并审核实际主任务证据送达，评分一致。规则审阅为未盲化开发审阅。545份来源运行期间和结束时均未变，15个关键加载类与准备记录一致。

候选配置17/25正确，8份prod均仅telemetry不同：traceSamplePercent写为null而应按公开规则回退到各源配置。accounts首次错误在主任务第13轮，早于第一次COMPACT请求，不能只归因于压缩。文件导航进入13个实际主任务请求，但不证明模型正确应用规则。

候选动态38轮后预算停止，环境终态revision 2，20份preview仍为revision 1，无最终revision 3原生新鲜证据。第15轮write因遗漏overwrite=true在写入前拒绝，却被旧适配层归为EXECUTION_ERROR_OUTCOME_UNKNOWN，目标锁因此保留；第19轮修正请求被锁拦截，随后第20–38轮重复读取同一对文件。检查点未进入本动态实例，不能把该循环归因于检查点提示。源码确认现有检测只看末尾连续3个相同单工具签名，会漏掉A/B成对重复。摘要基线动态20/21正确，剩余worker-01决策动作错误；业务质量仍不通过。

原始目录：tmp/cm-full-live-20261005-03/；结束回执：tmp/cm-api-execution-full-20261005-03.json；结构结果/失败定位：tmp/cm-full-results-20261005-03.json与tmp/cm-full-failure-analysis-20261005-03.json；Java审计：tmp/cm-full-live-audit-20261005-03.json；公开规则复算：tmp/cm-full-public-rule-audit-20261005-03.json；送达审计：tmp/cm-full-source-delivery-20261005-04.json。旧轮与全部失败均保留。

下一步先离线修复原生write覆盖拒绝的确定性分类，直接从写入前检查生成无副作用结果，不根据任意工具错误文字推断，也不放宽超时/IO/未知/部分效果的安全边界。修复后仍需新的逐轮API授权才能验证模型效果。本轮授权已结束，剩余额度不复用。

### 写前覆盖拒绝修复的离线验收

旧实现的新增回归先出现NO_EFFECT_CONFIRMED与EFFECT_UNKNOWN不符的真实断言失败，修复后6项针对性回归通过。普通引擎固定响应验证写入、覆盖拒绝、显式修正和独立验证，旧已确认写入/拒绝/新确认写入的attempt为VERIFIED_SUCCESS/FAILED_NO_EFFECT/VERIFIED_SUCCESS。整仓208 suites/1668 tests、0失败/0错误、6跳过，14模块成功。新的零模型准备目录tmp/cm-full-prepare-20261005-06/已通过封存审计，546源码/15关键加载类核对一致；6项仍NOT_RUN，API实际效果待新逐轮授权。就绪记录tmp/cm-write-precheck-readiness-20261005-01.json。


## 第四轮：原生写前覆盖拒绝修复复验

用户在限定请求后回复“继续”，同一批已观察v1任务6实例全部尝试、0完整通过。共155实际请求，input 1179200/output 71818/total 1251018；cache hit 812288/miss 366912为input子项，6个付费失败均计入，全部用量可得。Java封存重评分完整性/一致性通过；独立Node公开规则与46份期望文件复算一致，来源送达复核通过。仍为未盲化开发审阅，不宣称外部验证或泛化/生产收益。

| 实例 | 根状态 | 实际主任务轮 | 压力压缩 | 请求 | total Token | 文件正确数 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| configuration-migration-c1_rolling_summary | COMPLETED | 21 | 2 | 23 | 191127 | 17/25 |
| configuration-migration-c2_frozen_original_context | COMPACT_FAILED | 8 | 0 | 8 | 50240 | 8/25 |
| configuration-migration-c3_candidate_context | COMPLETED | 27 | 1 | 28 | 235500 | 17/25 |
| changing-evidence-continuation-c2_frozen_original_context | COMPACT_FAILED | 17 | 0 | 17 | 113936 | 0/21 |
| changing-evidence-continuation-c3_candidate_context | BUDGET_EXHAUSTED | 37 | 0 | 37 | 326762 | 8/21 |
| changing-evidence-continuation-c1_rolling_summary | BUDGET_EXHAUSTED | 40 | 2 | 42 | 333453 | 21/21 |

原生分类修复在摘要基线配置的2个覆盖拒绝中实际生效：FAILED_NO_EFFECT，随后各有一次VERIFIED_SUCCESS更新，全轮无T-SEG-002目标锁阻断。单独从CRC32/序号/快照重建账本并关联原生调用、目标、参数、失败原因和调用时间；初版仅用参数摘要将初次成功创建与后来拒绝混淆，失败审计保留，修正方法独立另存。候选动态没有覆盖拒绝事件，不能声称该候选直接覆盖了这2个用例。

候选配置17/25，8份prod的maxReplicas/traceSamplePercent错误复制null，公开回退规则未正确应用。候选动态成功读取revision 1/2/3并完成62个确定性文件写入验证，但21份最终文件仅8份符合业务规则；write副作用确认不等于业务正确。最新revision 3完整来源进入第27–29轮主任务请求，第30–37轮变为26字符[tool output — 3797 bytes]占位符，期间无文件检查点提示、无满足冻结口径的L2/L3压力压缩。实际执行过L2，但相关beforeStatus为OK，不能表述为从未发生L2；检查点只看原始预算预警或summary，漏掉了压缩决策计入预留后发生的来源丢失；早期动作错误发生在原文仍可见时，因此不能把全部失败归因于遮蔽。摘要基线动态21/21文件正确，但因预算停止仍未计任务完成，原门禁不改。

原始目录tmp/cm-full-live-20261005-04/；结果tmp/cm-full-results-20261005-04.json；失败定位tmp/cm-full-failure-analysis-20261005-04.json；结束回执tmp/cm-api-execution-full-20261005-04.json；Java审计tmp/cm-full-live-audit-20261005-04.json；公开规则审计tmp/cm-full-public-rule-audit-20261005-04.json；送达审计tmp/cm-full-source-delivery-20261005-05.json；账本复核tmp/cm-full-write-outcome-audit-20261005-02.json。546源码运行中及结束时不变，15关键加载类核对一致。本轮授权结束，余量不复用。下一步先离线补齐低于预警的来源遮蔽提示；任何模型效果仍需新逐轮授权，不通过调低评分、填充轮次或增加旧轮预算制造成功。


### 第四轮压缩级别补充复核（离线，无新API）

逐条重读compact_completed事件：候选动态第18、26–30、33、36、38轮均实际执行L2_EXTRACTIVE，beforeStatus均为OK。第30轮最新原文被遮蔽，11323→10864 Token；它不符合冻结门禁要求的预警压力条件，原压力计数0有效。此前“无实际L2/L3压力压缩”只应指无符合该口径的事件，不应理解为从未运行L2。独立补充证据tmp/cm-full-source-loss-clarification-20261005-01.json保留逐条事件和来源链，原封存与评分未改。


### 来源丢失触发修复的离线验收

修复后10项针对性回归通过。整仓clean verify：14模块成功，13模块顶层Surefire XML共208 suites/1670 tests、0失败/0错误、6跳过；日志tmp/cm-source-loss-checkpoint-clean-verify-20261005-01.log。新零网络准备tmp/cm-full-prepare-20261005-07/为ready=true、6 NOT_RUN、0 Provider、空初始历史；独立Java封存审计完整性和结果一致性通过，546来源/15关键加载类与干净构建一致，原版Context JAR及冻结v1任务不变。就绪回执tmp/cm-source-loss-checkpoint-readiness-20261005-01.json。

该修复尚未调用真实模型，不能更新第四轮评分或宣称效果提升。所有旧失败保留，CM-E4与持续目标未完成；下一轮仍需按docs/context-memory-evaluation.md取得新数据范围/预算授权，不复用旧余量。


## 第五轮：压缩后来源导航修复复验

用户在限定复验问题后回复“继续”，同一批已观察v1的2任务×3组共6实例全部尝试，2完整通过：摘要基线配置、候选动态。候选配置仍失败，整体live-full门禁false。共164实际请求，input 1180910/output 71933/total 1252843；cache hit 818816/miss 362094为input子项，6个付费失败均计账，用量全部可得。

| 实例 | 根状态 | 实际主任务轮 | 压力压缩 | 请求 | total Token | 文件正确数 | 完整结果 |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| configuration-migration-c1_rolling_summary | COMPLETED | 22 | 2 | 24 | 200384 | 25/25 | PASS |
| configuration-migration-c2_frozen_original_context | COMPACT_FAILED | 10 | 0 | 10 | 67893 | 2/25 | FAIL |
| configuration-migration-c3_candidate_context | COMPLETED | 27 | 2 | 29 | 240849 | 16/25 | FAIL |
| changing-evidence-continuation-c2_frozen_original_context | COMPACT_FAILED | 17 | 0 | 17 | 104714 | 1/21 | FAIL |
| changing-evidence-continuation-c3_candidate_context | COMPLETED | 39 | 2 | 44 | 326176 | 21/21 | PASS |
| changing-evidence-continuation-c1_rolling_summary | COMPLETED | 38 | 2 | 40 | 312827 | 18/21 | FAIL |

候选动态首次完整满足原门禁：39次实际主任务响应、2次符合口径的压力压缩，revision 3实际原生读取并送达主任务，21/21最终业务产物正确。导航进入22个主任务请求，8次在最新状态原文缺失时附带与原生read一致的来源引用。只证明本次已观察合成任务的回归通过；不能据单次各组结果给出压缩增益、留出泛化或生产效率结论。

候选配置16/25正确：8份prod的traceSamplePercent仍为null，指定preview/migration-summary.json缺失。实际写入的是preview/summary.json，路径与字段均不符合任务；评分按越出指定产物范围的写入记1次FORBIDDEN_WRITE_ATTEMPT，源文件及production保持不变。这次写入本身成功，并非被工具拒绝；最终模型将24项结构验证和错误汇总文件当作完成。规则原文第2–10轮可见，第11–17轮丢失、第18–21轮重读后可见，之后再次遮蔽；初次prod错误在第13轮，部分遥测错误在重读后的第20–21轮仍持续，不能仅归因于压缩或把导航送达当作语义应用。公开结构验证器不检查转换值，也不覆盖最终汇总文件，原评分和环境合同保持不变。

Java独立封存/重评分完整性和结果一致性通过；Node公开规则先独立重建46个期望产物再比对Gold，评分一致。另复核原生来源送达、CRC账本及导航，均为未盲化开发审阅。全轮0覆盖拒绝/未知attempt/目标锁阻断；其中一个原版配置实例未生成快照，按账本生产合同“快照可选、journal为事实来源”审核，v2辅助审计的缺文件失败保留，v3不放宽journal校验。546来源运行期间及结束时不变，启动时15关键加载类与准备/干净构建一致。

原始tmp/cm-full-live-20261005-05/；结构结果tmp/cm-full-results-20261005-05.json；失败定位tmp/cm-full-failure-analysis-20261005-05.json；结束回执tmp/cm-api-execution-full-20261005-05.json；Java审计tmp/cm-full-live-audit-20261005-05.json；公开规则tmp/cm-full-public-rule-audit-20261005-05.json；来源送达tmp/cm-full-source-delivery-20261005-06.json；账本tmp/cm-full-write-outcome-audit-20261005-03.json；导航tmp/cm-full-checkpoint-delivery-audit-20261005-01.json。旧轮和全部失败均保留，本轮授权已结束，余量不复用。下一步离线补强任务规则保留与精确相对路径，不以增强评测验证器提供答案、放宽门禁或填充轮次制造通过。CM-E4及主目标未完成。


### 第五轮后：任务说明原文保留的离线交付

候选Context新增有界保留：最近USER明确引用的路径，选取其后至多2个路径的最新read返回，被选返回最多4096字符；原完整工具交换连同同批其他返回/调用参数等总开销8192字符，不复制为SYSTEM。L2/T3/L3共享同一集合，成本进入原预算，不能容纳仍失败关闭；不回填已遮蔽原文、不判定当前文件新鲜或业务正确。相对路径提取保留完整文件名，排除URI后缀和斜线枚举；裸文件名等未识别情况沿用原导航。

另修复派生锚点清理按文本误删TOOL数据的边界，只清理SYSTEM派生片段。原版ConstraintExtractor改为原版冻结JAR私有加载，构造器/公共方法ABI不变，回归直接确认原版旧路径后缀和候选完整路径不同。原版manifest/JAR、Gold、任务、公开结构验证器、评分和预算均未改动。原先紧贴分词阈值的来源丢失夹具改用更明确的保护路径，保留原130行载荷、16384窗口/2048预留、原始OK+实际L2、导航实际成本和重新读取/写入/下一run隔离的严格断言，失败调试日志全部保留。

针对性整合18项通过；最终clean verify 14模块SUCCESS，13测试模块顶层XML共210 suites/1679 tests、0失败/0错误、6跳过。日志tmp/cm-task-source-clean-verify-20261005-02.log。长任务prepare tmp/cm-full-prepare-20261005-08/为6 NOT_RUN/0 Provider/空初始历史；正式prepare tmp/cm-frozen-prepare-20261005-08/为216 NOT_RUN/0网络请求、12压力预检均通过。两份独立Java封存/重评分审计通过，549来源和17个实际加载关键类匹配，原版JAR哈希仍为6c873e848fa30c8ead45d46279c8f6ea7afc9db70dd4ccc4e5ff51c7035c4469。完整回执tmp/cm-task-source-readiness-20261005-01.json。

以上仅为离线机制与准备证据，新修复实际模型调用0；第五轮已封存结果和全部失败不改。下一次同批6长任务仍需新逐轮范围/预算授权；未把旧余量或准备复用为授权，也不把单次动态回归通过当作因果收益或泛化结果。目标仍未完成。


## 第六轮：任务说明保留与相对路径修复复验

用户回复“允许本轮复验”后，执行同一批已观察 v1 的 2 个自建合成任务、3 组各 1 次，共 6 实例。候选配置任务和摘要基线动态任务通过，合计 2/6；候选动态任务仍失败，整体 live-full 门槛未通过。任务、公开结构验证器、Gold、评分和预算保持冻结，本轮属于修复回归。

| 实例 | 根状态 | 实际主任务轮 | 有效压力压缩 | 请求 | total Token | 文件正确数 | 完整结果 |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| configuration-migration-c1_rolling_summary | COMPLETED | 19 | 1 | 20 | 166282 | 17/25 | FAIL |
| configuration-migration-c2_frozen_original_context | COMPACT_FAILED | 7 | 0 | 7 | 43574 | 6/25 | FAIL |
| configuration-migration-c3_candidate_context | COMPLETED | 27 | 2 | 29 | 250561 | 25/25 | PASS |
| changing-evidence-continuation-c2_frozen_original_context | COMPACT_FAILED | 17 | 0 | 17 | 104600 | 0/21 | FAIL |
| changing-evidence-continuation-c3_candidate_context | COMPLETED | 37 | 1 | 42 | 312731 | 20/21 | FAIL |
| changing-evidence-continuation-c1_rolling_summary | COMPLETED | 39 | 2 | 41 | 319679 | 21/21 | PASS |

共 156 次实际请求，input 1127865、output 69562、total 1197427；cache hit 784640 / miss 343225 为 input 子项。6 个付费失败响应全部计账，用量均可得，所有实例均在约定预算内。请求模型为 deepseek-v4-flash，响应元数据为 deepseek-flash，分别记录。一次性授权已结束，剩余额度不复用。

### 本轮证据

- 候选配置首次原生读取 docs/migration.md 后，后续 26/26 次付费主任务请求均包含同一调用链的完整 TOOL 原文；候选动态首次读取 docs/decisions.md 后为 36/36。首次读取前各有 1 次请求，不计入保留覆盖分母。两份规则均只原生读取 1 次。这支持本轮可观察的原文保留效果，不能据此推出所有约束的语义正确性或跨任务收益。
- 候选配置两次 WARN 压力 L3：11943→7735、12002→8198 Token；候选动态一次 WARN 压力 L3：12939→7182 Token。各事件的必要锚点丢失列表为空；同时用实际请求逐字确认规则仍在 TOOL 角色中。token 缩减不等于端到端成本或正确率提升。
- 候选动态已原生读取并向实际主任务送达最终 revision 3，61 次文件写入均为 VERIFIED_SUCCESS，受保护文件不变、0 越界写入尝试、0 目标锁阻断。最终 21 个文件中 20 个业务正确，唯一错误为 worker-01 的 action。
- 错误产生于第 26 轮主任务（provider call-31）。完整决策规则及 revision 3 原文均在该输入中：health=UNHEALTHY、dependencyHealthy=true、maintenance=false、desiredRunning=true、authorizedRestart=true、冷却已结束，规则要求 PROPOSE_AUTHORIZED_RESTART，模型却写入 REQUEST_APPROVAL。同一轮 L3 后规则仍完整保留，不能把这个错误归因于原文被压缩丢失。结构验证器不检查 action 值，原验证器和评分保持不变。本任务仅生成预览文件，未执行服务器修复。

Java 从封存产物重算的完整性、结果一致性通过，整体门槛 false；Node 另行转写公开规则、复核原生送达、CRC/序号账本和规则保留覆盖，评分一致。以上均为未盲化开发审阅，不称为外部或盲评。549 份源码运行期间及结束时无变化，原版 Context JAR、41 源码 manifest、正式 216 原始数据和完整任务数据哈希未变。

原始目录 tmp/cm-full-live-20261005-06/；结果 tmp/cm-full-results-20261005-06.json；失败定位 tmp/cm-full-failure-analysis-20261005-06.json；结束收据 tmp/cm-api-execution-full-20261005-06.json；Java 审计 tmp/cm-full-live-audit-20261005-06.json；公开规则 tmp/cm-full-public-rule-audit-20261005-06.json；送达 tmp/cm-full-source-delivery-20261005-07.json；账本 tmp/cm-full-write-outcome-audit-20261005-04.json；规则保留与语义错误复核 tmp/cm-full-retained-rule-review-20261005-06.json，对应独立重算脚本同名 .mjs。

### 剩余工作

本修订的候选两任务没有同时通过，CM-E4 和主目标继续开放。第五轮候选动态通过与第六轮候选配置通过不能拼成一轮 2/2；重复观察过的 v1 也不能用作新留出集。下一步先梳理应用层规则、证据和决策一致性的校验职责，并区分业务判定、结构验证、文件副作用验证。当前错误不支持继续扩大保留预算，也不支持把题目答案写入通用 Context/Engine 或改评分。新的模型复验仍须独立授权；泛化结论需要另行冻结未观察任务，当前不宣称生产效率收益。


### 第六轮后：通用收尾指引的离线交付

继续追溯 worker-01 的原始提议：第 4 轮 revision 1 的 WAIT 正确；第 15 轮 revision 2 已在规则和最新证据均可见时误写 INVESTIGATE，规则要求提出已授权重启建议；第 26 轮 revision 3 又误写 REQUEST_APPROVAL。首次 L3 在第 26 轮，因而不能将第 15 轮误判归因于生成式摘要。独立追溯见 tmp/cm-task-closure-source-review-20261005-01.json，原第六轮评分与失败保持不变。

[smolagents 官方文档](https://huggingface.co/docs/smolagents/main/guided_tour#customizing-agent-termination-conditions)通过调用方提供的 final_answer_checks 定义收尾校验；[Anthropic 的 Agent 评测方法](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)区分最终环境结果、轨迹与重复执行的一致性。CLAWKIT 的领域校验应来自可信应用规则，与评测 Gold 隔离。当前先在普通 ASK/AUTO 的系统提示加入内容核对指引，要求按用户明确指定的规则和最新读取证据检查条件优先级、默认值和例外，区分提议、预览与实际执行。它不改变 Run 状态机或文件验证的机械判定，也不增加强制模型轮次。

本次 clean verify 14 模块 SUCCESS，210 suites / 1679 tests / 0 failure / 0 error / 6 skipped。零网络 full prepare tmp/cm-full-prepare-20261005-09/ 为 6 NOT_RUN、0 Provider、空初始历史；frozen prepare tmp/cm-frozen-prepare-20261005-09/ 为 216 NOT_RUN、0 网络请求、12 压力预检通过。两套独立 Java 封存/评分审计通过，549 来源和 17 个关键加载类匹配；相较第六轮，关键加载类仅共享 AgentEngine 改变。逐个核对六组初始输入边界：移除新增指引后消息完全相同，工具 schema 和请求参数完全相同；三组共用这项 Runtime 修改，不能将将来的差异仅归因于候选 Context。原版 JAR、既有数据、公开结构验证器、评分与预算未改。

就绪收据 tmp/cm-task-closure-readiness-20261005-01.json；输入边界审阅 tmp/cm-task-closure-boundary-review-20261005-01.json。新增实际模型调用为 0，收尾指引的效果尚未验证；后续同批 6 实例必须取得新的逐轮授权，旧余量不复用。CM-E4 和主目标继续开放。


## 第七轮：通用收尾指引回归

用户明确“确认”本轮范围后运行同批 2 个自建合成任务 × 3 组，共 6 实例，全部尝试、0 完整通过。相较第六轮，关键加载类仅共享 AgentEngine 的通用收尾指引变化；六组初始消息、工具 schema 及请求参数的其余内容一致。v1 已观察，本轮是回归，不能提供新的留出集泛化结论，也不能用单次前后差异认定提示导致性能变化。原数据、Gold、结构验证器及预算不改。

| 实例 | 根状态 | 实际主任务轮 | 有效压力压缩 | 请求 | total Token | 文件正确数 | 完整状态 |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| configuration-migration-c1_rolling_summary | BUDGET_EXHAUSTED | 34 | 3 | 37 | 321105 | 25/25 | FAIL |
| configuration-migration-c2_frozen_original_context | COMPACT_FAILED | 5 | 0 | 5 | 28921 | 4/25 | FAIL |
| configuration-migration-c3_candidate_context | COMPLETED | 24 | 1 | 25 | 222334 | 17/25 | FAIL |
| changing-evidence-continuation-c2_frozen_original_context | COMPACT_FAILED | 16 | 0 | 16 | 97021 | 1/21 | FAIL |
| changing-evidence-continuation-c3_candidate_context | COMPLETED | 34 | 2 | 35 | 289270 | 20/21 | FAIL |
| changing-evidence-continuation-c1_rolling_summary | BUDGET_EXHAUSTED | 38 | 2 | 40 | 321913 | 18/21 | FAIL |

共 158 次实际请求，input 1208426、output 72138、total 1280564；cache hit 814204 / miss 394222 为 input 子项。6 个付费失败响应全部计账，所有用量可得，六实例在授权预算内。请求模型为 deepseek-v4-flash，响应元数据为 deepseek-flash，分别记录。本轮授权已结束，余额不复用。两项摘要基线因预算预留门禁终止；实际已用 Token 低于额度上限不等于预算器错误。

### 来源和失败定位

- 通用收尾指引在六实例的 151/151 次实际付费主任务请求中各出现一次，但候选两项业务质量均失败。不能把提示送达、Run COMPLETED 或文件写入验证当作规则已正确应用。
- 候选配置首次读取规则后，23/23 次付费主任务请求保留完整 docs/migration.md 原 TOOL 文本。第 14 轮 WARN L3 为 12070→7737 Token。生产环境原文只送达到 provider call-13，生成式摘要未保留 domain=example.test，模型未重读环境配置，从第 14 轮起将 8 份 prod 输出写成 upstream.prod.example.test；公开规则要求 upstream.example.test。该错误同时暴露字段来源丢失和未核验推断，不能仅因规则文档仍在场就断言所有输入条件完整，也不能根据时间相邻证明单一因果。25 次确定写入中仅 17 个文件业务正确。
- 候选动态首次读取规则后，33/33 次实际主任务请求保留完整 docs/decisions.md；最终 revision 3 已原生读取并实际送达。第 21 轮 provider call-21 在完整规则和最新状态均可见时，worker-01 应写 PROPOSE_AUTHORIZED_RESTART，却写 INVESTIGATE；首次有效压力压缩在第 22 轮，因此该动作错误不能归因于此次压力摘要。52 次文件写入均 VERIFIED_SUCCESS，最终仍只有 20/21 文件业务正确。任务只生成预览，未执行服务器修复。
- 候选动态两次 WARN 压力事件为 L3 11662→7207、L2 11554→11530 Token，必要锚点丢失列表为空。压缩前后估算仅证明触发，不能替代端到端成本或正确率收益。
- 六组受保护输入均未变化，0 越界写入尝试；候选两组没有覆盖前置拒绝或目标锁阻断。Java 重评分与 Node 公开规则独立转写、原生来源送达、CRC/序号写入账本及保留覆盖全部核对一致；审阅为未盲化开发审阅。运行期间及结束时 549 来源 / 17 关键加载类 / CLI 包哈希与授权冻结版本一致。

### 本轮结论和下一步

通用收尾指引不足以保证当前两任务正确完成。第六轮配置 PASS 与本轮动态部分正确不能拼成一轮通过；长任务质量门槛仍未满足，CM-E4 和持续目标保持未完成。下一步先离线拆解两类缺口：压缩后关键字段的有来源重读，以及调用方提供的规则—证据—产物验收。领域判定归应用层，不能把评测答案或任务特例写入通用 Engine/Context；公开结构校验和副作用验证的含义保持不变。此次没有启动新的 API 轮次，也没有安排再跑 216 实例；新的真实调用须在方案与离线门禁完成后取得对应授权。

原始封存 tmp/cm-full-live-20261005-07/；结果 tmp/cm-full-results-20261005-07.json；全部失败定位 tmp/cm-full-failure-analysis-20261005-07.json；授权及结束收据 tmp/cm-api-authorization-full-20261005-07.json、tmp/cm-api-execution-full-20261005-07.json。Java 审计 tmp/cm-full-live-audit-20261005-07.json；公开规则 tmp/cm-full-public-rule-audit-20261005-07.json；送达 tmp/cm-full-source-delivery-20261005-08.json；账本 tmp/cm-full-write-outcome-audit-20261005-05.json；保留 tmp/cm-full-retained-rule-review-20261005-07.json；指引与错误源核对 tmp/cm-full-guide-delivery-review-20261005-07.json。


### 第七轮后：来源可见性与单次应用验收的离线交付

已将来源导航分解为历史原文的 PRESENT/MISSING 状态，依据原生调用ID及返回摘要在实际模型上下文中匹配；缺失引用优先展示，失败重读撤销旧引用，不缓存或恢复原文。加入可选调用方 TaskCompletionCheck，普通文本结束与工具 COMPLETE 均接受已配置验收；最多2次纠正，原权限/取消/deadline/预算不变，异常、拒绝或耗尽不进入 COMPLETED。合同与用法见 [文件来源和验收](context-memory-file-checkpoint.md)。

8条调用方验收测试和9条来源导航测试通过，其中原始6条验收回归先全部失败。两类离线示例使用真实文件操作纠正域名与变化后的revision/动作；固定模型响应仅证明机制。最终 clean verify 14模块成功，211 suites / 1689 tests / 0 failure / 0 error / 6 skipped；552来源与23关键加载类匹配。零网络 full prepare tmp/cm-full-prepare-20261005-10/ 为6 NOT_RUN；frozen prepare tmp/cm-frozen-prepare-20261005-10/ 为216 NOT_RUN、12压力预检通过；两套封存独立审计通过。就绪收据 tmp/cm-task-acceptance-readiness-20261005-01.json。

当前CLI及v1评测默认尚未装配具体业务验收回调，因此本轮不是新的模型效果实验，第七轮0/6结果和全部历史失败不改。接下来应由应用提供公开验收合同并接入新的评测修订，单独说明反馈与共享Runtime条件；不能将调用方规则写入通用Engine，或把Gold当成模型可用答案。当前新增实际API调用0，未申请或安排新轮次，质量门禁继续开放。


### 第七轮后：公开规则验收 v2 的离线接入

已建立 context-memory-live-full-acceptance-v2，与v1分别封存。三个Context组共用 PUBLIC_WORKFLOW_ACCEPTANCE_V1：在完成前逐项检查公开迁移映射/汇总或当前动态事实/动作优先级/汇总，以及保护源；内部判等不把正确值反馈给模型，只反馈最多8个错误位置、规则码和来源引用。检查只读、最多两次纠正、原预算和权限继续生效。旧v1任务数据、环境推进、Gold、评分及全部失败保留；默认CLI和v1不启用此项领域验收。

6条新版合同测试通过；部分正确产物仅为Gold制作的检查器单元夹具，不进入真实模型，也不作为效果证据。生产普通运行路径的固定响应证明未完成任务被拒绝，并在两次有界续接后停止；其供应商UNKNOWN，真实main计数0。最终14模块 clean verify：212 suites / 1695 tests / 0 failure / 0 error / 6 skipped。555来源和26加载类匹配；新版与兼容v1各6 NOT_RUN、frozen 216 NOT_RUN和12压力预检均通过，三套隔离JVM封存审计一致。回执 tmp/cm-public-acceptance-readiness-20261005-01.json。

本阶段真实模型调用0，未证明质量或成本改善。v2改变的是三组共享的程序辅助条件，两个任务已被观察，只能用于开发回归；若后续实测通过，仍需分别报告程序验收和模型表现，不能覆盖v1失败、跨版本拼接或仅归因压缩。原第七轮0/6成绩及整体未通过状态保持不变。


## 公开规则验收 v2：首轮真实复验

用户明确“允许本轮 v2 复验”后执行同批 2 个自建合成任务 × 3 组，共 6 实例，全部尝试、1 完整通过。v2 为三个 Context 组共同增加应用公开规则验收；任务已被观察，本轮是开发回归，不是新留出集，也不能与 v1 前七轮合并计算提升。原 v1 数据、评分与失败仍保留。默认 CLI 和 v1 评测未装配此项业务验收。

| 实例 | 根状态 | 实际主任务轮 | 有效压力压缩 | 请求 | total Token | 文件正确数 | 完整状态 |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| configuration-migration-c1_rolling_summary | COMPACT_FAILED | 32 | 3 | 36 | 319331 | 9/25 | FAIL |
| configuration-migration-c2_frozen_original_context | COMPACT_FAILED | 10 | 0 | 10 | 71107 | 0/25 | FAIL |
| configuration-migration-c3_candidate_context | BUDGET_EXHAUSTED | 35 | 2 | 37 | 327136 | 25/25 | FAIL |
| changing-evidence-continuation-c2_frozen_original_context | COMPACT_FAILED | 14 | 0 | 14 | 92067 | 0/21 | FAIL |
| changing-evidence-continuation-c3_candidate_context | BUDGET_EXHAUSTED | 36 | 3 | 41 | 325757 | 16/21 | FAIL |
| changing-evidence-continuation-c1_rolling_summary | COMPLETED | 37 | 2 | 39 | 307299 | 21/21 | PASS |

### 用量与审核范围

实际 177 请求，input 1367058、output 75639、total 1442697；cache hit 906746 / miss 460312 为 input 子项。8 次付费失败均保留并计账，没有缺失或估算用量；每实例和整轮均在授权限额内。所有请求记录为 deepseek-v4-flash，169 个成功响应的元数据为 deepseek-flash；8 个协议失败未保留实际模型名，不据此补填元数据。授权已结束，未使用额度不复用于新轮次。

Java 独立 JVM 从封存原始产物重评分，完整性和结果一致性通过。Node 另行转写公开规则并核对 Gold、实际文件、原生读结果与付费请求送达、写入 journal 的 CRC/序号/快照、规则保留和反馈边界；全部与记录一致。属于同一开发者的事后非盲审阅，不称外部或盲评。结束时 555 份来源、26 个关键加载类、CLI 包、原版 JAR 与授权冻结哈希一致，原始目录未修改。

### 程序验收实际表现

- C1 配置在第 24 个实际主任务轮提出结束，验收发现 16 项 dev/staging URL 错误，返回 RETRY；错误位置、规则码和来源反馈送达后续 8 次付费主任务请求，最终因摘要调用失败而停止，未证明纠正成功。
- C2 动态第 12 轮提出结束，验收发现尚未达到最终 revision、文件缺失等 19 项问题，返回 RETRY；反馈送达后续 2 次付费主任务请求，随后必要锚点超预算而停止。
- C1 动态第 37 轮提出结束，验收 ACCEPT；独立重评分确认 21/21 文件、原生新鲜读取和压力门槛均通过。
- 两个 C3 候选都未提出结束，完成验收回调未被触发。不能把配置了回调当作验收已覆盖所有中间状态，也不能把两次正确阻断写成纠正成功率。反馈不包含正确答案值，回调不写文件、不增加权限，最多两次纠正的限制保持不变。

### 候选失败定位

**配置迁移：产物正确，收尾失败。** 25/25 文件正确，35 次实际主任务调用和 2 次有效压力压缩，但根状态 BUDGET_EXHAUSTED。最后一次预览写入发生在主任务第 28 轮，之后又有 7 次付费读取请求，共 69175 total Token；八个静态配置来源各读了 4–5 次。最后模型明确因 gateway/accounts 仍标为 MISSING 而继续重读，没有提出完成。下次请求需保守预留 41059 Token，而剩余 32864，故在发送前停止。MISSING 只说明最新一次原始 TOOL 返回不在当前上下文中，不说明来源已过期或产物尚未完成；导航语义可能促成重复读取，尚不能断言单一因果。文件正确不替代根任务完成，评分仍为 FAIL。

**动态证据：最终修订更新尚未完成。** 最终 revision 3 已原生读取并实际送达；36 次实际主任务、3 次有效压力压缩，16/21 文件正确。worker-09/10 的两对决策/证据文件不匹配最终事实，汇总文件缺失；最后请求仍在写 worker-07 的修订产物，没有提出结束。下次请求需预留 41665 Token，剩余 34243，发送前停止。不能仅凭中断时的旧产物推断模型已完成最终决策，也不能据此认定只需增加预算。

配置规则在首次读取后完整保留并送达 34/34 个候选主请求，动态规则为 35/35；这是可见性证据，不是语义使用准确率。C2 首次规则读取也确实完整送达（2517/2421 字节），后续重读有 1024/1026 字符的缩减结果，不能采信“全部规则都被截断”的自述，也不能归因于原生 ReadTool 的 8000 字节阈值。

所有保护源检查通过，0 越界写入尝试。C3 配置 25 次写入均 VERIFIED_SUCCESS；C3 动态 56 次 VERIFIED_SUCCESS、2 次覆盖前置拒绝为 FAILED_NO_EFFECT，无未知写入结果。副作用确定不等于任务业务正确，任务仅生成隔离预览，未执行远程修复。

### 结论与后续离线门槛

候选两任务没有同时通过，CM-E4 和主目标继续开放；本轮不支持简历中的压缩正确率、Token 节省或运维效率提升。C1 的一次动态 PASS 不能与历史候选 PASS 拼接，也不能据本轮单次观察宣称其普遍优于候选。

接下来先使用封存轨迹离线复现：① 产物已正确后，来源可见性提示是否造成重复重读；② 低剩余额度时的输入收缩、工作优先级及动态修订传播。保持硬预算、权限、独立评分和失败记录；不通过提高额度、放宽预留或补齐模型调用轮数获得通过。本轮没有追加 API 调用，也没有安排重跑 216 实例。再做真实复验前，应先给出能复现具体缺口的离线证据和新修订。

原始封存 tmp/cm-full-acceptance-live-20261005-01/；结果 tmp/cm-public-v2-results-20261005-01.json；失败分析 tmp/cm-public-v2-failure-analysis-20261005-01.json；授权/结束收据 tmp/cm-api-authorization-public-v2-20261005-01.json、tmp/cm-api-execution-public-v2-20261005-01.json。Java 审计 tmp/cm-full-acceptance-live-audit-20261005-01.json；Node 公开规则 tmp/cm-public-v2-public-rule-audit-20261005-01.json、来源送达 tmp/cm-public-v2-source-delivery-20261005-01.json、写入账本 tmp/cm-public-v2-write-outcome-audit-20261005-01.json、规则保留 tmp/cm-public-v2-retained-rule-audit-20261005-01.json、反馈送达 tmp/cm-public-v2-feedback-delivery-audit-20261005-01.json、结束核对 tmp/cm-public-v2-post-run-audit-20261005-01.json。审计辅助脚本的失败和修正版另留 tmp/cm-public-v2-audit-helper-attempts-20261005-01.json；没有改写封存数据或得分。


### v2 后第一批离线修复（未进行模型复验）

澄清来源 MISSING 的含义，并补齐普通 ReAct 的成组只读重复提示：至少两个可信内置调用，实际结果成功且确认无效果，连续三批参数与返回文本相同才提示一次。仍允许新鲜采证，缺失产物的完成请求仍由应用验收拒绝；提示不计算答案、不自动完成任务。多个不同文件组轮换读取尚未覆盖，因此不能宣称上一轮候选配置的全部冗余读取已消除。

整仓 clean verify 为 14 模块成功、1700 项/0 失败/0 错误/6 跳过；新版与兼容长任务各 6 NOT_RUN、冻结样本 216 NOT_RUN/12 压力预检，均零模型调用。最终三套封存的独立 JVM 审计通过，557 来源/27 加载类匹配；原版、任务、Gold、评分和预算预留保持原字节。回执见 `tmp/cm-read-batch-readiness-20261005-01.json`，合同见 [文件来源与进度检查点](context-memory-file-checkpoint.md#公开验收-v2-后第一批重复读取提示)。

上表实际结果仍为 v2 首轮 1/6、候选 0/2。本版本没有真实模型结果，不填补成绩、不声称效率或质量收益；低余额输入收缩和动态修订传播仍待离线方案。


### 2026-10-06 第二批离线修复（真实结果尚未复验）

补齐多组轮换读取提示：原生可信、成功且确认无效果的多调用批次，在连续读取达到6批且回到最近12组中的相同参数/返回时提示一次。原连续3批提示保留，返回变化、写入或不合格结果重置；只保存摘要，必要的新鲜读取和公开业务验收继续有效。该运行时由三组共用，不证明模型已经减少重复读取。

full-task 三组共用可选输入规划，提前用原 ProviderRequestLimitGateway 的 UTF-8 请求预留评估真实余额；较小规划容量与实际账本分开，发送前硬门禁仍是最终检查。候选 GENERAL 则在较小容量下继续 L2，按完整 assistant/tool 交换移除旧历史，保护全部 SYSTEM/USER、任务说明来源和最近3组；可选锚点按既有比例规划，必要锚点不足仍失败。原版 Context、数据、Gold、独立评分和环境推进保持原字节，未提高额度或减少预留，未增加额外摘要调用。

最终构建包重放首轮 v2 的两个封存末端输入，使用其实际已计账用量：

| 候选实例 | 原发送预留 | 修订后预留 | 原剩余额度 | 离线结果 |
| --- | ---: | ---: | ---: | --- |
| 配置迁移 | 41059 | 31836 | 32864 | 能通过原预算口径 |
| 动态证据 | 41665 | 24666 | 34243 | 能通过原预算口径 |

回放0模型调用、0摘要调用，没有生成新任务产物，只证明该保存输入可发送。仅缩小规划时两项均未通过；仅移除完整交换时配置仍超105，动态已容纳；全部失败与最终结果保留。不能把上下文估算下降记作实际 Token 节省，也不能保证下一次模型能完成收尾或动态修订传播。

65项定向回归通过，包含真实缺口的先失败后通过；最终 clean verify 为14模块、214 suites/1711项、0失败/0错误/6跳过。v2和兼容v1各6 NOT_RUN；frozen216 NOT_RUN/12压力预检，继续旧输入规划构造，不安排实跑。三套封存独立JVM审计通过，558来源/33关键加载类与最终构建匹配。就绪回执 `tmp/cm-progress-budget-readiness-20261006-01.json`；最终载荷回放 `tmp/cm-input-plan-saved-context-replay-20261006-05.json`；合同见 [长任务第7节](context-memory-live-full-plan.md#7-发送预留与输入规划离线修订)。

本阶段真实模型调用0，最新实际成绩仍为上表v2首轮1/6、C3 0/2。CM-E4及主目标继续开放；新版6实例真实开发回归须另取本轮数据范围/预算授权，旧授权不复用，不与v1或216分母混合。
