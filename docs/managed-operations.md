# 分层自治运维 CLI 使用说明

此版本管理**本地隔离 Linux Docker Compose** 的无状态服务，并将已登记的 **SSH/MCP 远程只读来源**接入同一诊断与持续事件流程。隔离服务支持分层授权与独立恢复验证；远程来源当前支持观测、调查、归并与交接。

需要 Java 21。隔离修复需要 Docker CLI、Compose 和运行中的 Linux Docker daemon；远程只读来源需要 OpenSSH 与已登记、可通过校验的远端 MCP。Windows 隔离服务可用 Docker Desktop 的 `desktop-linux` context；Linux 通常使用 `default`。`run` 和 `diagnose` 需要模型凭据；登记、状态、已保存诊断、配置检查、暂停和撤销不调用模型。

安装包解压后包含 `clawkit.jar`、Windows 启动器 `clawkit.cmd`、Linux 启动器 `clawkit.sh` 和隔离夹具。Linux 可执行 `sh ./clawkit.sh` 或 `java -jar ./clawkit.jar`；下方示例为 PowerShell。

## 1. 检查入口

```powershell
.\clawkit.cmd --version
.\clawkit.cmd autonomy --help
.\clawkit.cmd autonomy status
```

登记数据默认放在 `~/.clawkit/autonomy`；试用建议指定新的 `--state-dir`，与其他应用的数据分开。登记固定容器身份，不能用另一个容器覆盖已有应用 ID；重建夹具时使用新的应用 ID 或新的试用目录。

## 2. 登记远程或隔离服务

下方为隔离服务；接入现有远端请看“远程只读接入”。

### 远程只读接入

先使用现有服务器登记流程保存 `~/.clawkit/remote-targets.yaml`，确认 SSH 主机密钥与 MCP 工具合同。`remote-register` 引用这个目标，不接收主机、私钥或远端命令。

```powershell
# 容器 ID 必须换成刚核对的真实返回值；12 位引用仅可用于只读来源。
.\clawkit.cmd autonomy remote-register remote-orders --state-dir .\remote-state `
  --target test-server --environment order-api-drill --service order-api `
  --container-id '<已核对的容器ID>' --interval 60
.\clawkit.cmd autonomy check remote-orders --state-dir .\remote-state
.\clawkit.cmd autonomy diagnose remote-orders --state-dir .\remote-state --details
.\clawkit.cmd autonomy run remote-orders --state-dir .\remote-state --once
.\clawkit.cmd autonomy status remote-orders --state-dir .\remote-state
```

远端环境名是人工登记的逻辑范围；旧探针不提供完整的项目、daemon 与动作条件证明。每个新会话重新读取连接登记、核对登记指纹并完成现有握手校验；返回事实还须匹配工具、服务、容器和真实采集时间。连接登记改变时拒绝继续。停止旧控制实例、保留旧证据目录，审阅后使用新的 `--state-dir` 和应用 ID 重新登记；当前同一数据目录不允许第二个应用重复登记相同远端容器。

若已有独立探测，登记时可添加：

- `--health-endpoint <白名单名> --health <远端loopback健康URL>`。
- `--business-endpoint <白名单名> --business <远端loopback业务URL> --marker <成功标记>`。
- `--metrics-endpoint <白名单名> --metrics-probe-url <远端loopback指标URL>`。

这些 URL 用于核对远端来源，不从客户端访问。端点名必须已存在于远端白名单；不会创建探测。未配置的业务、健康、依赖和指标明确保留为缺证；运行中的容器或累计指标不能单独证明业务健康。遥测为单次快照，没有历史内存趋势时不推算趋势。

远端日志固定为最近五分钟、最多五十行，进入诊断前脱敏并限制摘要长度；本地隔离来源使用下文的两分钟窗口。来源时间保留原值；服务器时间最多领先客户端五秒时等待客户端追上，超出范围则记为来源错误，不改写证据时间。`diagnose` 和 `run` 会把规范化证据发送给配置的模型服务；`remote-register`、`check` 和查询已保存结果只在本地处理。

远程登记默认且仅支持 `observe`。现有 `opsfix/restart_service` 未提供完整身份、条件版本及幂等回执，不能开启 `ask` 或 `limited-auto`，也不能绑定要求完整身份的外部告警。`pause/resume/stop/events/diagnosis` 复用现有入口。下一步修复接入范围见 [实施合同第10节](../docs/layered-autonomy-implementation-plan.md#10-后续sshmcp-远程自治接入)。

### V2 修复部署候选与审阅

本地已实现固定重启的独立服务端合同和客户端适配器；普通远程登记仍为只读。安装包的 `remote-gateway/` 包含独立 `ops-mcp.jar`、`SHA256SUMS.txt`、SSH entry、root gateway 和权限模板。配置草稿见 [默认禁用示例](../examples/autonomy/pinned-restart-v2-disabled.json)，逐项审阅见 [order-api 清单](../examples/autonomy/remote-order-api-repair-review.json)。示例含占位身份、过期时间和 `statelessReviewed=false`，不能直接激活动作。

真机部署前需要填写实际完整容器、daemon、本地 Docker socket、Compose 和全部只读挂载摘要、依赖、审阅声明及不超过 30 分钟的授权期限。还需将独立健康/业务探测纳入已有只读白名单，补齐完整身份的只读证明和客户端资格绑定。根所有配置不是用户审批，也不是模型准确率证明。

审阅固定安装位置：JAR/校验文件位于 `/opt/clawkit-ops-v2/`；配置 `/etc/clawkit-ops-v2/scope.json`；持久化回执 `/var/lib/clawkit-ops-v2/`；gateway `/usr/local/sbin/clawkit-ops-v2-gateway`；SSH entry `/usr/local/bin/clawkit-ops-v2-entry`。需要 Linux、Java 21、Docker CLI、GNU coreutils、sudo 和 OpenSSH；配置、JAR、脚本、状态及祖先目录由 root 所有，其他身份不可写，脚本使用 LF 且有执行权限。使用独立 SSH 身份/公钥和精确无参数 sudoers 条目；审阅模板后先以 `visudo -cf` 核对，不能自动覆盖已有 opsro/opsfix。root gateway 清空继承环境并核对独立 server JAR 哈希。包内不提供自动安装器。

每个服务器授权版本只允许一个派发 intent，第二次条件检查拒绝也占用额度。状态未知时保留客户端 Attempt 和服务器 intent/receipt，先暂停控制器并人工核对；续期不解锁未知，更不能删除记录以重试。撤销时将配置 `enabled=false` 或等待到期，暂停/停止控制器并保留日志；恢复仅接受新独立观测，不把重启退出码当成功。Linux 状态执行文件和目录同步；当前 Windows 隔离结果不是断电持久性认证。详细合同见 [第10.7节](../docs/layered-autonomy-implementation-plan.md#107-v2-固定重启合同与本地交付)。

开发者可在仓库根目录以候选 JAR 运行隔离验收；输出目录必须尚不存在且位于仓库 `tmp/`。它仅使用本地 Linux Docker daemon，为唯一随机项目故障注入与重启，最后清理所属容器，保留记录；不调用模型和 SSH。

```powershell
java -cp .\clawkit-cli\target\clawkit-cli-0.1.0.jar `
  com.clawkit.ops.delivery.managed.PinnedRestartFixtureEvidenceMain tmp/remote-r2-my-fresh-trial
```

### 创建可丢弃的隔离试用环境

先确认 `clawkit-autonomy-demo` 是本次试用的新项目。夹具会占用本地 18180／18181 端口；端口冲突时改变下面两个环境变量和随后登记的 URL。

```powershell
$env:AUTONOMY_ORDERS_PORT='18180'
$env:AUTONOMY_CATALOG_PORT='18181'
docker --context desktop-linux compose -f .\ops-fixtures\layered-autonomy\compose.yaml -p clawkit-autonomy-demo up -d --wait

.\clawkit.cmd autonomy register orders --state-dir .\demo-state `
  --compose .\ops-fixtures\layered-autonomy\compose.yaml --context desktop-linux `
  --project clawkit-autonomy-demo --service orders --dependencies catalog --stateless `
  --health http://127.0.0.1:18180/health --business http://127.0.0.1:18180/orders --marker accepted
.\clawkit.cmd autonomy check orders --state-dir .\demo-state
```

登记和检查均不修复；初始权限为“需人工审批”。只允许已登记容器的启动／重启，不执行任意 Shell、不修改镜像／配置、不重启依赖。Compose 文件、只读挂载内容、Docker daemon 或容器身份改变时，检查会拒绝继续。

## 3. 选择处置权限

```powershell
# 只调查与交接
.\clawkit.cmd autonomy policy orders observe --state-dir .\demo-state

# 对固定动作请求审批
.\clawkit.cmd autonomy policy orders ask --state-dir .\demo-state

# 人工审阅这个可丢弃目标，限定动作并授权 30 分钟
.\clawkit.cmd autonomy policy orders limited-auto --state-dir .\demo-state `
  --actions start,restart --minutes 30 --confirm-reviewed `
  --review-note '已核对隔离订单服务、无状态声明及启动/重启范围'
```

`--confirm-reviewed` 是用户对具体目标和动作范围的人工确认，记录为范围审阅；不等于模型准确率、正式评测通过或生产目标晋级。Agent 无法调用权限配置命令。授权到期需要重新配置；持久化失败降级不会因为续期而清除。

## 4. 运行与查看

通过环境设置 `CLAWKIT_API_KEY`，不要写入登记文件、审阅备注或日志。默认使用项目配置的模型；可在 `run` 上指定 `--model`、`--base-url` 和 `--protocol`。

```powershell
# 前台持续运行；保持此终端打开
.\clawkit.cmd autonomy run orders --state-dir .\demo-state

# 或只执行一个观察／决定周期，完成后退出
.\clawkit.cmd autonomy run orders --state-dir .\demo-state --once
```

另一终端可查询，无须再次调用模型：

```powershell
.\clawkit.cmd autonomy status orders --state-dir .\demo-state
.\clawkit.cmd autonomy events orders --state-dir .\demo-state --limit 20
.\clawkit.cmd autonomy handoff orders --state-dir .\demo-state
```

状态显示应用、目标、控制进程是否运行、当前权限、事件、最近观察时间、标准事实、动作及结果。默认用中文展示服务／健康／业务／依赖事实；`status --details` 或 `events --details` 可展开模型判断和事件细节，模型判断不替代事实。控制进程未运行时，这是已保存的历史观察。事件归并不会为相同异常反复调用模型；等待／补证有截止时间和次数限制。成功要求新会话中的服务、健康、业务连续通过；命令退出码 0 不等于恢复。

### 多源调查与已保存诊断

`diagnose` 调查当前状态，提出假设和下一步，不执行修复；`diagnosis` 查看最近保存的结果，不再次调用模型。持续运行的 `run` 使用相同诊断合同，再进入既有授权、现场复查与独立验证链。

```powershell
.\clawkit.cmd autonomy diagnose orders --state-dir .\demo-state
.\clawkit.cmd autonomy diagnosis orders --state-dir .\demo-state --details
```

诊断显示支持证据、反证、未知项和替代解释。日志限最近两分钟、最多 200 行与有界摘要，先脱敏；空结果不代表没有错误，截断会明确标记。资源采集提供 Docker 的 OOMKilled、退出信息及可得的当前内存快照；没有历史指标时不推断内存趋势。旧错误日志不能单独推翻当前健康业务。证据过期、来源不完整或相互冲突时，Agent 需要补证或交接。

每次调查有独立记录，包含引用、时间窗、来源状态和内容哈希。模型诊断是推断；配置、依赖和资源问题不因一条“重启建议”获得额外动作权限。默认产品记录不包含原始模型推理轨迹。

已采集到当前 OOMKilled 或截断来源时，程序拒绝启动和重启建议，保留调查并转交人工。服务处于停止状态也不豁免这项条件。初始服务/健康/业务/依赖事实由产品采集，Agent 按假设选择额外来源，过期或冲突时再刷新。

### 导入人工或 CI 变更记录

变更记录必须对应已登记的应用、环境、服务和应用版本。下面示例对应第 2 节的 `orders` 登记；把版本及摘要换成实际变更。不要填密钥。导入不会修改服务配置或发布应用。

```powershell
@{
  id='release-v2'; applicationId='orders'; applicationVersion=1
  environment='clawkit-autonomy-demo'; service='orders'
  occurredAt=(Get-Date).ToUniversalTime().ToString('o')
  configurationVersion='v2'; summary='更新了接口配置，需核对运行版本'
  operator='local-user'
} | ConvertTo-Json | Set-Content -Encoding utf8 .\change.json
.\clawkit.cmd autonomy change-import orders --state-dir .\demo-state --input .\change.json
```

相同 ID 的相同内容可重复导入；内容不同则拒绝覆盖。调查按最近 24 小时读取有界记录。变更时间接近故障只能支持关联，配置原因还需要日志或其他事实佐证。

### 可选内存历史

已有本地 Prometheus 和 cAdvisor 时，可显式登记指标来源：

```powershell
.\clawkit.cmd autonomy metrics orders --state-dir .\demo-state --metrics-url http://127.0.0.1:9090
.\clawkit.cmd autonomy metrics orders off --state-dir .\demo-state
```

仅用固定模板查询已登记容器的 `container_memory_working_set_bytes`，读取最近五分钟、30 秒步长，并限制响应、序列和样本数。缺失、过期、NaN、warning、身份不符及超时均保留状态；没有这套指标服务也能使用其他调查能力。

每次 Agent 运行默认预算为 120 秒、6 次模型请求、12 次工具调用和 30,000 Token；调查阶段单次输出上限 4096，诊断接受后的最终决定最多 1024。相同证据快照只检索一次处置知识，新采证后可再次检索；最终提交被拒绝后重新开放调查。登记检查和初始发现按各只读采集器的限制执行。多源诊断默认关闭额外原生思考，将假设、证据和不确定性写入结构化诊断。输出截断或预算耗尽会留档并交接，不算完成诊断。

### 处置知识与复盘

知识默认是草稿。新版本先进行有正例和反例的只读回放，再由用户明确审阅；检索只返回当前环境、服务和应用版本下的已审阅内容。命中结果显示知识版本、匹配依据及当前事实是否符合条件。历史案例不证明本次根因，知识审阅也不增加应用动作权限。

`run` 在独立恢复或转交人工后保存复盘草稿，固定当时的输入、证据哈希与结果。人工交接保留为交接，不记为恢复；模型根因保留为待确认推断。查看与审阅不再调用模型：

```powershell
.\clawkit.cmd autonomy cases orders --state-dir .\demo-state
.\clawkit.cmd autonomy postmortem orders --state-dir .\demo-state
.\clawkit.cmd autonomy case-review orders <案例ID> --state-dir .\demo-state `
  --confirm-reviewed --cause DEPENDENCY_FAILURE --review-note '根据独立调查确认依赖故障'
.\clawkit.cmd autonomy case-revoke orders <案例ID> --state-dir .\demo-state --review-note '原先确认的原因被新证据推翻'
```

流程版本示例见 [依赖故障流程](../examples/autonomy/dependency-runbook-v1.json)。使用前把 `scope` 改为登记中的实际环境、服务和应用版本；`sourceCaseIds` 可以引用已人工审阅的同范围案例。不能覆盖已有版本；更新使用新的 `version`，错误版本使用撤销。

```powershell
.\clawkit.cmd autonomy knowledge-import orders --state-dir .\demo-state --input .\runbook.json
.\clawkit.cmd autonomy knowledge-list orders --state-dir .\demo-state
.\clawkit.cmd autonomy knowledge-replay orders dependency-outage@1 --state-dir .\demo-state --input .\replay.json
.\clawkit.cmd autonomy knowledge-review orders dependency-outage@1 --state-dir .\demo-state `
  --replay-id <回放ID> --confirm-reviewed --review-note '已检查正例、反例与禁用条件'
.\clawkit.cmd autonomy knowledge-search orders --state-dir .\demo-state --query '依赖不可用'
.\clawkit.cmd autonomy knowledge-revoke orders dependency-outage@1 --state-dir .\demo-state --review-note '适用条件需要修订'
```

回放文件包含 `sampleVersion` 和 2..24 个 `samples`；每个样本为 `id`、登记中的 `application`、判定时间 `at`、诊断记录中的 `evidence` 和事前预期 `expectedApplicable`。可复用隔离诊断保存的事实，另设超过证据有效期的反例；修复流程还应覆盖真实 OOM、依赖失败、截断或不适用版本。程序只核对流程条件，不调用模型或执行修复。输入与全部失败结果留存，缺少正例/反例或有失败时不能通过审阅。

处置建议引用检索版本并在提交时检查条件。执行前锁定知识版本、重新读取所需事实，仍通过现有应用授权、现场检查和独立验证；撤销、哈希变化或条件不适用会交接。执行持有版本检查锁期间，知识变更会提示忙碌，需要稍后重试。案例确认原因不能原地改写；撤销错误案例会使引用它的知识失效。当前支持固定采证及已有启动/重启流程，没有任意命令或自动配置变更。

有/无知识的受控运行时对照仅验证相同工具、模型参数、预算和记录合同；实际诊断收益留待新版本冻结评测，不从这些门禁测试推算成功率。

### 可选 Alertmanager 告警接入与关联

接入默认关闭。先登记应用，再用环境变量提供 32..256 字符的独立接入凭据，并绑定这个应用。凭据只保存哈希，不放进命令参数或告警正文。多个应用可逐一绑定；每次绑定或关闭来源都会更新配置版本，旧版本尚未消费的消息进入待核对状态。

```powershell
# 在启动接收器的终端中设置；不要使用模型或飞书密钥
$env:CLAWKIT_ALERT_TOKEN=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
.\clawkit.cmd autonomy alert-bind orders --state-dir .\demo-state
.\clawkit.cmd autonomy alert-listen orders --state-dir .\demo-state --port 8778
```

接收器前台运行，仅监听 `127.0.0.1` 的 `POST /alertmanager`。Alertmanager 的同机进程使用相同凭据发送 Bearer 请求；配置片段见 [接收器示例](../examples/autonomy/alertmanager.yaml)。告警标签必须包含与登记完全一致的 `clawkit_environment` 和 `clawkit_service`。未知目标、截断内容和异常时间进入待核对，不根据告警里的应用 ID、URL 或命令创建目标。

另一个终端启动既有控制会话。接收器只入库；当前服务仍需现场采证，由同一授权与执行链决定调查、审批或有限自主处理。应用暂停/停止时消息保留，不开始新处置。

```powershell
.\clawkit.cmd autonomy run orders --state-dir .\demo-state
.\clawkit.cmd autonomy alerts orders --state-dir .\demo-state --details
.\clawkit.cmd autonomy relations orders --state-dir .\demo-state
.\clawkit.cmd autonomy relation-revoke orders <关系ID> --state-dir .\demo-state --review-note '独立调查不支持此关系'
.\clawkit.cmd autonomy alert-disable orders --state-dir .\demo-state
# 也可由本地用户导入同一 webhook v4 格式文件
.\clawkit.cmd autonomy alert-import orders --state-dir .\demo-state --input .\alert.json
```

`alerts` 和 `relations` 展示整个控制工作区的来源记录。相同来源、指纹、开始时间和状态构成重复投递，保留投递次数；恢复先到时，迟到的同次 firing 不会重新打开。来源 resolved 仅表示上游通知恢复，不关闭内部事件、不作为业务恢复证据。诊断记录保留读到的来源事件 ID；重复消息沿用已有事件，不重复调用模型或派发动作。

关联与去重分别记录：两个独立事件只有在相同环境/daemon、已登记且身份一致的直接依赖关系、开始时间相距不超过两分钟时，才生成可撤销关系。通知分组 `groupKey` 不证明共同根因。每个应用保持自己的事件、授权和审批；关联不能代替任何一个应用的许可。停止接入不停止已开始的控制会话。

首版每批最多 32 条、正文 64 KiB、消息回溯 24 小时；来源存储最多 256 条事件、256 条关系和 2 MiB 快照，达到上限即拒绝新写入并留待人工处理。默认不归档原始告警正文，只保留脱敏字段与正文哈希。当前验证覆盖本机 HTTP、重复/乱序/伪造目标、跨环境关联与持续入口；尚未部署真实 Alertmanager 验证，飞书卡片和真实送达另行验收。

## 5. 在夹具中注入故障

仅对上面本次创建的试用夹具使用以下入口。该入口不属于 Agent 工具，也不用于实际应用。

```powershell
# 订单服务异常持续到进程重启，限定自主策略可修复
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:18180/__fixture/fault `
  -ContentType application/json -Body '{"seconds":3600}'

# 或注入短暂异常，观察等待／自恢复行为
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:18180/__fixture/fault `
  -ContentType application/json -Body '{"seconds":8}'

# 目录依赖异常，重启订单服务不能消除它，应调查／交接
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:18181/__fixture/fault `
  -ContentType application/json -Body '{"seconds":3600}'
```

## 6. 审批、暂停和停止

先用 `status` 查看待审批目标、原因和事件 ID。明确批准或拒绝后，命令进入本地队列，只有持有该应用控制权的 `run` 进程消费。批准绑定应用与动作，有效期最多五分钟，消费时重新采证；排队并不表示修复完成。

```powershell
.\clawkit.cmd autonomy approve orders <事件ID> --state-dir .\demo-state --confirm
.\clawkit.cmd autonomy reject orders <事件ID> --state-dir .\demo-state
.\clawkit.cmd autonomy command-result orders <命令ID> --state-dir .\demo-state

.\clawkit.cmd autonomy pause orders --state-dir .\demo-state
.\clawkit.cmd autonomy resume orders --state-dir .\demo-state
.\clawkit.cmd autonomy stop orders --state-dir .\demo-state
```

`pause` 阻止新工作，并取消尚未派发的动作；已派发动作记录实际验证结果。`resume` 需要仍然运行的控制进程，否则重新执行 `run`。`stop` 或 Ctrl+C 停止控制并保留事件、执行记录和证据。

结果未知或恢复验证失败时自动处理持久化降级，不能靠重启控制器或反复批准重复同一动作。`handoff` 查看交接与证据；此版本不提供用“清状态”按钮将未知执行改为成功的路径。先由人工核对实际应用及执行记录。

## 7. 可选飞书通知

默认不发送。配置机器人环境凭据 `CLAWKIT_FEISHU_APP_ID`／`CLAWKIT_FEISHU_APP_SECRET` 后，使用 `run --notify --chat-id <明确选择的会话ID>` 开启；机器人需要能在该会话发言。Agent 不能选择接收者，也不能通过通知审批修复。

```powershell
.\clawkit.cmd autonomy run orders --state-dir .\demo-state --notify --chat-id <会话ID>
.\clawkit.cmd autonomy notifications orders --state-dir .\demo-state
```

只通知发现异常、待审批、恢复或人工交接等重要变化，重复观察不重复通知。通知先进入本地 Outbox，后台有界重试；发送失败不改变故障或修复结果。重试使用相同 UUID；[飞书官方 SDK 文档](https://larksuite.github.io/oapi-sdk-java/com/lark/oapi/service/im/v1/model/CreateMessageReqBody.Builder.html)说明服务端去重窗口为一小时。无法确认送达的旧派发在本地五十分钟窗口后停止重发，保留记录供核对，不承诺无限期恰好一次投递。

未配置飞书仍可完整运行。当前工程验证中的通知使用受控传输；未选定真实会话前，不计为真实飞书送达。

## 8. 证据与清理

每个应用的数据目录包含登记和范围审阅、当前事件、最近事件、决定摘要、人工命令回执、授权与 Attempt、独立验证和通知 Outbox。默认不保存原始模型请求／响应，运行评测时另行显式开启完整轨迹。不要把包含本地配置或私有信息的整个状态目录公开上传。

控制器不是系统后台服务；此版本不承诺全天在线或长期稳定性。一个控制工作区内，一个容器只能登记为一个应用；不同数据目录／外部 Docker 操作者不受同一互斥锁保护。

先停止 `run`，再仅清理本次创建的夹具：

```powershell
.\clawkit.cmd autonomy stop orders --state-dir .\demo-state
docker --context desktop-linux compose -f .\ops-fixtures\layered-autonomy\compose.yaml -p clawkit-autonomy-demo down
```

保留 `demo-state` 可以复查证据。重新创建容器后身份会改变，旧登记不能继续使用。
