# Agent Template Pro

面向 C 端自然语言业务应用的代码优先 Agent 脚手架。当前版本提供集团总 Agent 与领域子 Agent 两级路由、匿名访客隔离、SSE 聊天协议、参数收集、确认门禁、任务审计和可恢复时间线，以及不依赖真实模型和外部系统的四领域公开 Demo。

## 本地启动

要求：JDK 21、Maven 3.9+、Node.js 20+。

```bash
mvn -pl agent-boot -am package
mvn -f agent-boot/pom.xml spring-boot:run
cd ui-chat && npm install && npm run dev
cd ui-console && npm install && npm run dev
```

后端监听 `http://localhost:8080`；Chat 默认 Vite 地址为 `http://localhost:5173`，Console 为 Vite 分配的另一个端口。Console 公开演示账号为 `admin`，公开 Demo 密码为 `Admin123!`，登录时还需填写图片验证码。本地可在被 Git 忽略的 `application-dev.yml` 中覆盖凭据；部署前必须通过环境变量 `CONSOLE_USERNAME`、`CONSOLE_PASSWORD` 替换默认值。验证码和登录会话有效期可分别通过 `CONSOLE_CAPTCHA_TTL_SECONDS`、`CONSOLE_SESSION_TTL_SECONDS` 调整。

Demo 可输入：`明天上海还有海景房吗`、`周末体育馆有哪些比赛`、`带老人去上海哪个景区合适`、`离岛免税的香水有哪些`。酒店、体育、文旅和免税分别注册为独立 `DomainModule`，由总 Agent 先选择领域，再由领域 Agent 从自己的动作白名单中选择动作。写操作继续经过补参、任务、确认和幂等门禁。

默认启动使用进程内 RuntimeStore，不要求 MySQL 或 Redis。要启用 MySQL 事实存储和 Flyway 迁移，先提供数据源环境变量，再同时启用 Maven 与 Spring Profile：

```bash
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/agent_pro?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
export SPRING_DATASOURCE_USERNAME='agent_pro'
export SPRING_DATASOURCE_PASSWORD='replace-me'
SPRING_PROFILES_ACTIVE=runtime-jdbc mvn -Pruntime-jdbc -f agent-boot/pom.xml spring-boot:run
```

`runtime-jdbc` 会将会话、领域路由决定、补参、任务、确认快照、SSE 事件、Outbox、Tool 执行、审计和 Graph checkpoint 持久化到 MySQL。启用 `graph-checkpoint-redis` Spring Profile 后，Redis 只作为 checkpoint 加速层；Redis 不可用时会回退到 RuntimeStore，不会丢失已持久化事实。MCP 控制面已支持受控 Server 配置、能力发现、能力版本快照、Agent 固定版本绑定和审计；HTTPS transport 在连接前拒绝内网/保留 DNS 地址并固定已校验 IP，写 Tool 的 Console Debug 固定返回冲突且不会执行。知识库已提供受控 TXT/Markdown/PDF/DOCX 上传、页数与提取文本上限、版本化文档、MySQL 索引任务租约、切片启停、删除补偿和脱敏 citation 检索。Console 上传以 JSON 传输，源文件严格限为 10 MiB；二进制文件以 Base64 编码后仍受 15 MB WebFlux 解码上限和服务端解码后字节复核保护。PDF 使用受限工作区，DOCX 在解包前施加条目数、单条目、总展开量与 POI zip-bomb 限制。默认仍为文件系统对象存储与确定性开发检索；生产部署应同时启用 `knowledge-s3` 和 `knowledge-embedding-jdbc`，前者使用受限 HTTPS S3/OSS 兼容 endpoint，后者通过 Spring AI `EmbeddingModel` 将真实 embedding 持久化到 MySQL 向量表，二者均不会回显密钥或对象键。

OpenAI-compatible 模型可独立启用。模型负责生成总 Agent 的普通对话回复，以及候选领域、候选动作与参数；普通对话会携带代码预设的总 Agent 身份、公开领域能力和最近 12 条有效消息，由模型结合上下文生成自然回复。Runtime 仍会按服务端 Agent 注册表、动作所有权、补参、确认、幂等和状态机重新校验，模型生成的文本不能直接执行写操作：

```bash
export OPENAI_API_KEY='replace-me'
export OPENAI_BASE_URL='https://your-openai-compatible-endpoint'
export OPENAI_CHAT_MODEL='your-model'
SPRING_PROFILES_ACTIVE=model-openai mvn -Pmodel-openai -pl agent-boot -am package
SPRING_PROFILES_ACTIVE=model-openai java -jar agent-boot/target/agent-boot-0.1.0-SNAPSHOT.jar
```

未启用模型 Profile 时，应用使用确定性意图解析器与预设对话回复。`model-openai` Profile 缺省保持 `spring.ai.model.chat=none`，因此可在没有密钥时完成构建和健康检查；真实启用模型时还需设置 `AGENT_CHAT_MODEL_PROVIDER=openai` 和有效 `OPENAI_API_KEY`。模型调用失败、返回空回复或未返回合法路由 JSON 时会安全回退。总 Agent 的 `message.final` 事件使用 `generationSource=MODEL` 或 `generationSource=PRESET_FALLBACK` 标识回复来源，避免把降级文本伪装成模型回答。

生产知识库示例（S3/OSS 端点必须为部署配置的 HTTPS 地址，禁止将其暴露给 Console）：

```bash
export AGENT_KNOWLEDGE_S3_ENDPOINT='https://s3.example.com'
export AGENT_KNOWLEDGE_S3_ALLOWED_HOSTS='s3.example.com'
export AGENT_KNOWLEDGE_S3_BUCKET='agent-pro-knowledge'
export AGENT_KNOWLEDGE_S3_REGION='cn-hangzhou'
export AGENT_KNOWLEDGE_S3_ACCESS_KEY_ID='replace-me'
export AGENT_KNOWLEDGE_S3_SECRET_ACCESS_KEY='replace-me'
export AGENT_EMBEDDING_MODEL_PROVIDER='openai'
export OPENAI_EMBEDDING_MODEL='text-embedding-3-small'
SPRING_PROFILES_ACTIVE=runtime-jdbc,knowledge-s3,knowledge-embedding-jdbc,model-openai \
  mvn -Pruntime-jdbc,model-openai -f agent-boot/pom.xml spring-boot:run
```

DashScope 使用独立的 Maven/Spring Profile。Starter 中 Agent、Embedding、Image、Video、Rerank 和 Audio 默认保持关闭，只显式启用 ChatModel，避免未配置无关服务密钥时启动失败：

```bash
export AGENT_CHAT_MODEL_PROVIDER='dashscope'
export DASHSCOPE_API_KEY='replace-me'
export DASHSCOPE_CHAT_MODEL='qwen-plus'
mvn -Pmodel-dashscope -pl agent-boot -am package
SPRING_PROFILES_ACTIVE=model-dashscope java -jar agent-boot/target/agent-boot-0.1.0-SNAPSHOT.jar
```

Runtime 已加入 Spring AI Alibaba 1.1.2.2 的 Graph Core 和 Agent Framework，并通过本地无网络测试验证 Graph 流、`RunnableConfig`、内存 checkpoint、ReactAgent 流式/非流式 Tool 调用、`outputType` 结构化输出、动作白名单和高风险 Tool 拒绝策略。旧 MCP 聚合依赖已排除，严格 Maven 依赖收敛检查通过。M1 已实现确认并发、Outbox、结果未知恢复、可信回调推进、受访客归属约束的恢复 API、默认关闭的后台恢复调度和持久化 checkpoint；临时 MySQL 8.4 已完成 V001-V004 迁移、重启后会话读取验证，Redis checkpoint Profile 也已完成真实连接启动验证。真实模型调用仍需要部署环境 Secret 和单独的供应商验收。

MiniMax 的 Anthropic 兼容接口使用独立适配器。当前本地连接模式通过环境变量注入密钥，并同时启用 MySQL Runtime：

```bash
export MINIMAX_API_KEY='replace-me'
export MINIMAX_ANTHROPIC_BASE_URL='https://api.minimaxi.com/anthropic'
export MINIMAX_MODEL='minimax-m.2.7-highspeed'
mvn -P runtime-jdbc -pl agent-boot -am package
SPRING_PROFILES_ACTIVE=dev,runtime-jdbc,model-minimax java -jar agent-boot/target/agent-boot-0.1.0-SNAPSHOT.jar
```

当前 Console 提供运行总览、领域 Agent 注册表、任务记录、Trace/Span、非敏感运行配置、模型/Prompt/MCP 管理、知识库管理、Agent 应用管理，以及数据集、评估器、实验和回归评估页面；Trace 与评估列表/详情均连接真实 API，采用服务端分页、筛选与明确空态，不返回密钥或敏感原文。知识库页面连接真实 API，包含服务端分页、文档上传与状态、失败任务重试、检索测试、citation 定位及切片启停；源文件、对象键、切片正文和密钥都不会返回给 Console。M2 已增加 Provider/Model 元数据、SecretRef 配置状态和 Prompt 草稿、版本、发布、回滚的管理 API。M5 已增加 Agent 应用与版本管理：应用分页/新建/归档、版本校验/发布/回滚、发布记录、API Key 创建/轮换/撤销和受控 OpenAPI 查看；版本发布固定模型、Prompt 版本、知识库和 MCP/Tool 版本，发布后不可变。API Key 仅以状态与前缀展示，明文只在创建/轮换时返回一次，撤销立即失效。`api-agent` 提供 `/api/agent/v1/apps/{appCode}/chat/completions` 开放接口，复用同一 Runtime 的会话、确认门禁、幂等与审计链，不建立旁路执行。SecretRef 仅保存环境变量、Kubernetes Secret 或外部 KMS 的引用，不通过 API、审计、日志或页面返回引用定位与密钥值；`configured` 由服务端解析结果产生，不能由前端声明。启用 `runtime-jdbc` 时管理员 Bearer 会话只以 SHA-256 摘要持久化，RBAC 权限从数据库角色映射读取，Provider/Model/Prompt/Agent 应用与追加审计也以 MySQL 为事实源；默认 Demo 使用相同门禁的内存回退。

Provider 连接测试目前 fail-closed：在部署具备“白名单主机到已绑定连接地址”能力的 Provider Adapter 前，服务不会发起网络请求，而是返回 `PROBE_UNAVAILABLE`。这避免了先 DNS 校验、再由 HTTP 客户端二次解析造成的 DNS rebinding 风险。ENV SecretRef 在调用时从环境变量解析；Kubernetes Secret 和 KMS 需部署对应 resolver 后才会显示为已配置。

控制面审计在应用协议中只有追加入口；Prompt 创建版本、发布或回滚与对应审计在同一 JDBC 事务中完成，回滚使用独立事件码并记录前后版本 ID。生产数据库账号还必须对 `agent_control_plane_audit` 仅授予 `SELECT`、`INSERT`，不得授予 `UPDATE`、`DELETE`；该限制由数据库账号权限实施，迁移不会要求 `SUPER` 或降低 binlog 安全设置。

M6 已交付可观测性与评估中心。Runtime 对请求、路由、动作、任务、确认、Tool、模型与知识检索统一埋 Span，`agent_runtime_span` 只落白名单字段与脱敏 metadata，Prompt/消息原文不进入 Trace；Console Trace 页支持按类型/状态/时间过滤、服务端分页和从请求到 span 的双向定位。评估中心包含数据集/版本/用例（手工、批量导入、从脱敏 Trace 生成候选）、确定性评估器插件与受控 LLM Judge、实验任务（启动/停止/重试/阈值/成本统计）。实验由 MySQL 数据库任务驱动，worker 使用 `FOR UPDATE SKIP LOCKED` 租约抢占，进程崩溃后重启会接管未完成的 RUNNING 实验继续执行，已验证同库重启恢复。评估用例通过 `RuntimeEvaluationExecutor` 走与访客 Chat、开放 API 完全相同的 `ChatOrchestrator` 链（路由、补参、确认门禁、幂等、审计），不存在旁路执行；无模型时确定性回退路径仍可被评估为可验证的路由/确认事实。`V012`（observability_evaluation）与 `V013`（visitor/eval 列宽修复）已同步至 V001 初始化定义。

M7 已交付受控 Workflow Studio。Workflow DSL（schema 1.0，DAG）支持 Start/End、Variable Assign、Input、LLM、Classifier、Action、MCP Tool、Retrieval 与 Parallel 节点；版本发布前做结构校验（单 Start、可达性、环检测、出边条件规则）与资源绑定校验，发布后版本不可变，可回滚到历史已发布版本并追加审计。运行以逐节点事实持久化（`agent_workflow_run`/`node_run`/`event`，节点唯一键与事件 sequence 唯一），写节点（Action/MCP Tool）复用 `ChatOrchestrator` 的确认门禁、幂等键与任务状态机：只有 `WAITING_CONFIRMATION` 版本化确认后才执行，拒绝/过期置 FAILED，外部结果未知（`RESULT_UNKNOWN`）禁止 retry；Input 节点复用 `form.request` 语义暂停运行。Console 支持整图调试运行、节点输入提交、确认/拒绝、暂停、恢复、停止、重试与 SSE 增量事件流（终态自动关闭）；失败节点保留入边供 retry 重新执行，进程强杀后启动清扫器把 stale RUNNING 置为 PAUSED 并恢复被中断节点，可手动恢复继续。RBAC 使用 `workflow:read/write/run`（ADMIN/OPERATOR 全部、VIEWER 只读），SSE 只暴露 read 事件。`V014`（workflow_studio）与 `V015`（workflow 审计 resource_id 列宽）已同步至 V001 初始化定义，空库一次完成 15 个 Flyway 迁移。
## 生产发布

项目通过 GitHub Actions 构建后，经 ECS SSH/FRP 跳板发布到家庭物理机。应用版本位于 `/home/app/agent-template-pro/releases/<tag>`，`current` 软链接负责原子切换；生产环境变量只保存在物理机的 `/home/app/agent-template-pro/shared/app.env`。Java 运行时按版本安装在 `/home/codex-ops/JDKs`，当前服务使用 `jdk-21` 软链接，不修改系统 Java，也不与未来的 JDK 8 或 JDK 17 混装。

- Chat：<https://manzhushaka.cn/gateway/agent-template-pro/chat>
- Console：<https://manzhushaka.cn/gateway/agent-template-pro/console>
- 健康检查：<https://manzhushaka.cn/gateway/agent-template-pro/health>

首次接入或发布链路变更使用候选发布：同一份制品通过远程预检、原子部署和健康检查后才创建最终 Tag 与 GitHub Release。常规发布由新的 `v*` Tag 触发。发布脚本位于 `.release/`，Home Nginx 路由事实源为 `deploy/agent-template-pro.locations.conf`；候选部署会自动激活并校验该路由，公网 Chat、Console 和健康检查全部通过后才允许创建 Tag。

## 模块

- `agent-common`：错误模型、脱敏和纯值对象。
- `agent-domain-spi`：领域模块与受控动作扩展契约。
- `agent-runtime`：领域 Agent 注册表、总 Agent 路由与对话生成契约、会话、参数收集、确认、任务状态机与事件协议。
- `agent-infrastructure`：签名访客身份、内存/JDBC 存储和 OpenAI/MiniMax 路由、意图与对话生成适配器。
- `agent-control-plane`：管理员 RBAC、共享会话、SecretRef 状态、模型、Prompt、MCP 工具治理、知识库与 Agent 应用发布用例。
- `api-agent`：面向第三方调用方的受控 Agent 开放 API（API Key 鉴权 + 已发布应用快照 + 同一 Runtime 执行链）。
- `api-chat` / `api-console` / `api-agent`：分别面向访客、管理端和第三方开放调用方的入站 API。
- `agent-demo`：酒店、体育、文旅和免税四个公开假数据示例领域。
- `agent-boot`：唯一默认启动模块；包含 MySQL 版 Flyway 建表迁移。

默认运行使用内存存储，便于新 clone 的开发者即刻验证交互；`runtime-jdbc` Profile 提供 MySQL `RuntimeStore` 和可恢复的 Console 会话。生产环境仍须将本地单管理员凭据替换为独立管理员目录与正式认证接入。

## API 约定

Chat 使用 `GET /api/chat/v1/bootstrap` 获取公开 Agent 元数据，使用 `POST /api/chat/v1/conversations/{id}/messages:stream` 和 `text/event-stream` 交互，并通过统一时间线恢复消息、路由、卡片和任务。浏览器不提交身份或 Graph thread ID；服务端使用签名 Cookie 解析访客并校验会话归属。高风险动作仅能通过任务 ID、确认版本和用户决定继续执行。

设计和扩展边界见 [智能体开发脚手架系统设计书.md](智能体开发脚手架系统设计书.md)。

## License

This project is licensed under the [MIT License](LICENSE).
