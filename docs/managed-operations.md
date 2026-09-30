# 分层自治运维 CLI 使用说明

此版本在**本地隔离 Linux Docker Compose** 中管理已登记的无状态服务。支持持续发现、事件归并、Agent 调查／等待／建议、人工批准或限定自主启动／重启、独立业务验证和人工交接。真实服务器自治接入另行选择目标与授权。

需要 Java 21、Docker CLI 与 Compose，以及运行中的 Linux Docker daemon。Windows 可用 Docker Desktop 的 `desktop-linux` context；Linux 通常使用 `default`。仅 `run` 需要模型凭据；登记、状态、配置检查、暂停和撤销不调用模型。

安装包解压后包含 `clawkit.jar`、Windows 启动器 `clawkit.cmd`、Linux 启动器 `clawkit.sh` 和隔离夹具。Linux 可执行 `sh ./clawkit.sh` 或 `java -jar ./clawkit.jar`；下方示例为 PowerShell。

## 1. 检查入口

```powershell
.\clawkit.cmd --version
.\clawkit.cmd autonomy --help
.\clawkit.cmd autonomy status
```

登记数据默认放在 `~/.clawkit/autonomy`；试用建议指定新的 `--state-dir`，与其他应用的数据分开。登记固定容器身份，不能用另一个容器覆盖已有应用 ID；重建夹具时使用新的应用 ID 或新的试用目录。

## 2. 创建可丢弃的试用环境

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
