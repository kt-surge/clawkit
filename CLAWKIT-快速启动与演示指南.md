# CLAWKIT 快速启动、演示与部署指南

## 1. 推荐结论

CLAWKIT 最适合采用 **本地主控 + 远端最小能力组件** 的混合部署：

- 用户电脑运行 CLAWKIT CLI、Agent Runtime、Session、Memory、Skills、审批交互和 Ops Console；
- 远端 Linux 只部署预定义、可审计的 MCP 能力组件；
- 普通真实服务器默认使用 `opsro` 只读身份；
- 写操作使用独立的 `opsfix` 身份和固定动作，当前应优先在可丢弃 Fixture 环境中演示；
- 不建议现阶段把整套 Agent Runtime 部署成服务器上的常驻服务。

这种方式既符合“本地优先的个人 AI 运维助手”定位，也能直接复用本地 OpenSSH 配置、SSH Agent 和 `known_hosts`，把模型密钥、长期记忆和人工审批留在用户设备上。

## 2. 环境要求

- Windows、macOS 或 Linux；
- Java 21；
- Maven 3.9 或兼容版本；
- 使用模型能力时需要 DeepSeek API Key；
- 连接真实服务器时，需要本地 OpenSSH 配置和可用的 SSH 凭据；
- 远端服务器为 Linux，并已安装 CLAWKIT 受限 MCP 组件。

在 Windows PowerShell 中检查环境：

```powershell
java -version
mvn -version
```

## 3. 最快启动

进入项目目录并构建 CLI：

```powershell
cd D:\Agent\miniclaw

$env:CLAWKIT_API_KEY = "<你的 DeepSeek API Key>"

mvn -q -pl clawkit-cli -am package "-DskipTests"

.\clawkit.cmd --root D:\Agent\miniclaw
```

如果项目已经构建过，只需：

```powershell
$env:CLAWKIT_API_KEY = "<你的 DeepSeek API Key>"
.\clawkit.cmd
```

`clawkit.cmd` 找不到可运行 JAR 时会自动执行构建。正式演示前建议预先构建，避免现场等待。

检查版本：

```powershell
.\clawkit.cmd --version
```

当前仓库验证版本为 `clawkit 0.1.0`。

## 4. 推荐演示结构

演示不应从泛化聊天开始，而应围绕一条清晰的问题解决链路展开：

```text
发现异常
  → Incident 创建或合并
  → 证据采集与诊断
  → 分层决策
  → 人工审批边界
  → 独立验证
  → Console 校验与回放
```

建议准备两套演示：

1. **稳定演示**：本地 Fixture + 离线 Console，不依赖远程服务器和模型网络；
2. **产品演示**：本地 CLAWKIT + 可丢弃 Linux 环境，展示真实 SSH/MCP 只读调查。

## 5. 三分钟稳定演示

### 5.1 创建隔离的演示环境

使用独立目录启动，避免污染个人会话、Memory 和服务器配置：

```powershell
cd D:\Agent\miniclaw

mvn -q -pl clawkit-cli -am package "-DskipTests"

$demo = Join-Path $env:TEMP ("clawkit-demo-" + [guid]::NewGuid().ToString("N"))
$demoHome = Join-Path $demo "home"
$demoWork = Join-Path $demo "work"
New-Item -ItemType Directory -Force $demoHome,$demoWork | Out-Null

$env:CLAWKIT_API_KEY = "<your-api-key>"

java "-Duser.home=$demoHome" `
  -jar .\clawkit-cli\target\clawkit-cli-0.1.0.jar `
  "--root=$demoWork"
```

这里设置的是 Fixture 演示占位值，不会调用模型。它不能用于正常模型任务。

### 5.2 展示 A0 Observe-only Loop

进入 CLI 后执行：

```text
/ops observe fixture start
/ops observe fixture status
```

重点说明：

- 当前信号为 `APP_DOWN`；
- 首次异常创建一个 ACTIVE Incident；
- 重复同类异常会合并到已有 Incident；
- `Provider budget: 0/0`，本流程不调用模型；
- 没有远程 target、人工审批或修复入口；
- A0 负责持续观察和状态收敛，不等于自动修复。

随后执行：

```text
/ops observe fixture stop
/ops observe fixture replay
/ops observe fixture snapshot
```

`replay` 展示已持久化的观测事件，`snapshot` 生成带 manifest 的唯一证据目录。

### 5.3 展示 A3 Shadow

保持 Observe Loop 已停止，继续执行：

```text
/ops observe fixture shadow
/ops observe fixture shadow-eval
/ops observe fixture shadow-review defer
```

重点说明：

- Shadow 只记录“在固定策略下，本会如何处理”；
- Shadow 不创建 A2 `ApprovalGrant`；
- Shadow 不调用 `opsfix`，不执行修复；
- 所有 A3 结果必须保持 `Side effects: 0`；
- 人工 review 是反事实选择，不是实际审批记录；
- A3 不能跳过 A2 直接进入 A4。

### 5.4 展示加速演练

```text
/ops observe fixture soak
```

该命令运行一个隔离的 Fixture Loop，覆盖暂停、恢复、状态波动和重启后的 Registry 恢复，并生成 `accelerated-soak-report.json`。

演示时必须说明：

- 这是 72 个**逻辑小时**的加速演练；
- 不是自然墙钟连续运行 72 小时；
- 不是线上故障样本；
- 不是自动修复成功率。

## 6. Ops Console 演示

在本机浏览器打开：

```text
D:\Agent\miniclaw\ops-console\dist\index.html
```

也可以在 PowerShell 中执行：

```powershell
Start-Process (Resolve-Path .\ops-console\dist\index.html)
```

将 `snapshot` 输出目录中的以下文件拖入页面：

- `fixture-evidence-manifest.json`
- `automation-state.json`
- `incident-registry.jsonl`
- `observation-timeline.jsonl`

如果执行过 `soak`，再导入：

- `accelerated-soak-report.json`

Console 会完成：

- 文件名和必需文件检查；
- 文件字节数与 SHA-256 校验；
- snapshot 批次绑定检查；
- Fixture A0 状态、Registry 和时间线语义检查；
- Incident、状态迁移、预算和演练报告展示。

Console 当前是**离线证据校验和回放界面**，不是远程控制台。它没有网络、调度、模型、审批和修复能力，也不能绕过 Agent Runtime 直接操作服务器。

## 7. 真实服务器只读演示

### 7.1 推荐环境

真实产品演示建议使用：

- 本地 Windows 运行 CLAWKIT；
- 一台可丢弃的 Linux 虚拟机或云服务器；
- Docker Compose 部署演示服务，例如 `order-api`；
- 服务器安装受限 `opsro` MCP 组件；
- 本地 `~/.ssh/config` 已配置目标 alias；
- 不在普通真实服务器开放 `opsfix` 写能力。

### 7.2 连接流程

进入 CLI 后执行：

```text
/remote add --from-ssh test-server
/remote doctor <targetId>
/remote connect <targetId>
/remote tools
```

各步骤的演示重点：

- `add`：从现有 OpenSSH 配置登记逻辑目标，不复制私钥；
- `doctor`：检查 SSH、认证、主机身份和远端能力合同；
- `connect`：建立受限 SSH/MCP 会话；
- `tools`：展示当前目标实际挂载的预定义远程能力。

### 7.3 启动调查

```text
/ops investigate <targetId> order-api "调查服务为什么不可用"
/ops recent
```

调查过程应重点展示：

1. 服务、容器、HTTP 和有界日志等多源证据；
2. 事实、推测、缺失证据和过期证据的区分；
3. Incident 的创建、保存和后续继续能力；
4. 诊断结论、影响范围、反证和不确定性；
5. 真实服务器默认保持只读。

### 7.4 展示运行可观测性

```text
/runs
/metrics <runId>
/trace <runId>
```

这里用于说明 Run/Turn、模型调用、工具执行和运行事件不是只打印在终端，而是可以通过结构化事件进行追踪和回放。

## 8. 审批修复如何演示

当前推荐在可丢弃 Fixture 中展示 A2 审批修复边界，不将其包装为生产服务器自动修复。

生成 A2 Fixture 证据：

```powershell
cd D:\Agent\miniclaw

mvn -q -pl clawkit-cli -am package "-DskipTests"

$output = Join-Path $env:TEMP ("clawkit-a2-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $output | Out-Null

java -cp .\clawkit-cli\target\clawkit-cli-0.1.0-shaded.jar `
  com.clawkit.ops.delivery.FixtureAskEvidenceMain `
  $output
```

生成的 `a2-fixture-evidence-report.json` 可以和有效 A0 snapshot 一起导入 Console。

这份报告固定展示两条安全路径：

- 模拟审批后执行受限动作，并由独立验证进入 `RESOLVED`；
- 派发后发生 I/O 中断，进入 `NEEDS_HUMAN`，后续 continue 不会重复派发。

两条路径都标记 `remoteWrites=0`。它们是进程内 Fixture 证据，不是真实 SSH 写入、人工审批记录或线上恢复数据。

## 9. 推荐部署拓扑

```text
用户电脑
├─ CLAWKIT CLI
├─ Agent Runtime
├─ Session / Working Memory / Long-term Memory
├─ Skills 与 MCP Client
├─ 模型 API Key
├─ 审批交互
└─ Ops Console
        │
        │ OpenSSH + MCP stdio
        ▼
远端 Linux
├─ opsro：预定义、可审计的只读能力
├─ 服务 / 容器 / HTTP / 有界日志
└─ opsfix：独立身份和固定动作，按环境单独评审
```

### 本地主控的优势

- 直接复用本地 SSH config、SSH Agent 和 `known_hosts`；
- API Key、长期记忆、会话和审批记录保留在用户设备；
- 用户可直接进行高风险动作审批；
- 一套本地 Runtime 可以登记并连接用户已有服务器；
- 不需要提前建设中心化账号、RBAC 和共享凭据系统；
- 符合个人开发者和小团队的产品定位。

### 不建议整套部署到服务器的原因

如果把整个 CLAWKIT Runtime 部署成服务器常驻服务，需要额外解决：

- 模型密钥和长期记忆托管；
- 用户登录、身份认证和 RBAC；
- 谁可以批准高风险写操作；
- 多用户会话隔离和审计；
- 其他服务器 SSH 凭据的安全保管；
- 后台进程健康检查、升级和回滚；
- Web 或 IM 入口的长期运行与故障恢复。

这些能力不属于当前个人本地助手的核心范围，也会稀释 Agent Runtime、工具安全和运维闭环的项目叙事。

## 10. Docker 是否适合作为主演示入口

仓库已经提供 Dockerfile，可以运行交互式 CLI：

```powershell
docker build -t clawkit:dev .

docker run --rm -it `
  -e CLAWKIT_API_KEY=$env:CLAWKIT_API_KEY `
  -v "${PWD}:/workspace" `
  -v "clawkit-home:/home/clawkit/.clawkit" `
  clawkit:dev
```

但 Windows 下的主演示仍建议使用原生 CLI，因为它更容易复用本机 OpenSSH 配置、SSH Agent、`known_hosts` 和终端交互。

Docker 更适合证明项目可打包、可隔离和可交付。目前容器只承诺交互式 CLI，不应描述成已经支持后台 IM Bot 或完整服务化部署。

## 11. 面试现场推荐流程

建议将完整演示控制在 5～8 分钟：

### 第一阶段：30 秒定位

> CLAWKIT 是运行在用户本地的个人 AI 运维助手。它通过受限 SSH/MCP 能力连接服务器，帮助用户理解服务为什么异常；需要执行动作时先解释和审批，执行后重新采证验证是否恢复。

### 第二阶段：1～2 分钟展示异常收敛

- 启动 Fixture Observe-only Loop；
- 展示 `APP_DOWN`、Incident 创建和预算；
- 说明重复异常不会制造大量重复 Incident；
- 强调 A0 无模型、无远程写入。

### 第三阶段：2～3 分钟展示分层自治

- 生成 snapshot；
- 展示 A3 Shadow 决策和 `Side effects: 0`；
- 说明 A3 只做反事实评估，真正执行仍回到 A2；
- 展示结果未知后禁止重复派发的 A2 Fixture 证据。

### 第四阶段：1～2 分钟展示 Console

- 导入 snapshot；
- 展示 manifest、hash 和语义校验；
- 回放 Incident 和状态变化；
- 说明 Console 不能直接执行写操作。

### 第五阶段：30 秒收束

> 这个项目的重点不是让模型拥有更多服务器权限，而是把观察、诊断、决策、审批、执行和验证拆成能够独立约束、逐层晋级和事后回放的闭环。

## 12. 演示口径边界

可以表述：

- 本地优先的个人 AI 运维助手；
- 本地 Agent Runtime 通过受限 SSH/MCP 连接远端 Linux；
- 支持真实服务器只读调查；
- Fixture 中已实现审批修复和独立验证闭环；
- A0 Observe-only 和 A3 Shadow 均可生成持久化、可回放证据；
- Console 可以校验 manifest、hash 和状态时间线。

不要表述：

- 已经在生产环境自动修复故障；
- 已经开放真实服务器的通用写权限；
- 72 小时加速 Fixture 是自然运行 72 小时；
- 100 个合成案例代表真实故障分布；
- A3 Shadow 已经替代人工审批；
- Ops Console 是远程控制服务器的 Web 管理后台。

## 13. 最终建议

日常开发和面试演示采用以下组合：

> **Windows 本地原生运行 CLAWKIT，连接一台可丢弃的 Linux/Docker Compose 演示环境；真实目标展示受限只读调查，审批修复使用 Fixture，Ops Console 展示可验证证据和分层自治边界。**

这条路径启动快、现场稳定，也最能体现项目的核心价值：Agent Runtime、上下文机制、工具安全和可验证的分层自治闭环。
