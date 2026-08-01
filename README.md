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

`runtime-jdbc` 会将会话、领域路由决定、补参、任务、确认快照、SSE 事件和 Outbox 持久化到 MySQL，并预置后续 MCP Tool 执行记录所需的表结构；Redis Graph checkpoint、MCP 和知识库尚未接入时，应用不会伪装为已连接这些服务。

OpenAI-compatible 模型可独立启用。模型负责生成总 Agent 的普通对话回复，以及候选领域、候选动作与参数；普通对话会携带代码预设的总 Agent 身份、公开领域能力和最近 12 条有效消息，由模型结合上下文生成自然回复。Runtime 仍会按服务端 Agent 注册表、动作所有权、补参、确认、幂等和状态机重新校验，模型生成的文本不能直接执行写操作：

```bash
export OPENAI_API_KEY='replace-me'
export OPENAI_BASE_URL='https://your-openai-compatible-endpoint'
export OPENAI_CHAT_MODEL='your-model'
SPRING_PROFILES_ACTIVE=model-openai mvn -Pmodel-openai -pl agent-boot -am package
SPRING_PROFILES_ACTIVE=model-openai java -jar agent-boot/target/agent-boot-0.1.0-SNAPSHOT.jar
```

未启用模型 Profile 时，应用使用确定性意图解析器与预设对话回复。启用 Maven Profile `model-openai` 后必须同时提供有效的 `OPENAI_API_KEY`，否则 Spring AI Starter 会拒绝启动；应用成功启动后，模型调用失败、返回空回复或未返回合法路由 JSON 时会安全回退。总 Agent 的 `message.final` 事件使用 `generationSource=MODEL` 或 `generationSource=PRESET_FALLBACK` 标识回复来源，避免把降级文本伪装成模型回答。

MiniMax 的 Anthropic 兼容接口使用独立适配器。当前本地连接模式通过环境变量注入密钥，并同时启用 MySQL Runtime：

```bash
export MINIMAX_API_KEY='replace-me'
export MINIMAX_ANTHROPIC_BASE_URL='https://api.minimaxi.com/anthropic'
export MINIMAX_MODEL='minimax-m.2.7-highspeed'
mvn -P runtime-jdbc -pl agent-boot -am package
SPRING_PROFILES_ACTIVE=dev,runtime-jdbc,model-minimax java -jar agent-boot/target/agent-boot-0.1.0-SNAPSHOT.jar
```

当前 Console 提供运行总览、领域 Agent 注册表、任务记录和非敏感运行配置。它只读展示实际生效的 Agent、存储、路由和模型信息，不支持在线新增 Java 动作或直接发布任意 Prompt；模型服务仍由受控环境配置选择。

## 生产发布

项目通过 GitHub Actions 构建后，经 ECS SSH/FRP 跳板发布到家庭物理机。应用版本位于 `/home/app/agent-template-pro/releases/<tag>`，`current` 软链接负责原子切换；生产环境变量只保存在物理机的 `/home/app/agent-template-pro/shared/app.env`。Java 运行时按版本安装在 `/home/codex-ops/JDKs`，当前服务使用 `jdk-21` 软链接，不修改系统 Java，也不与未来的 JDK 8 或 JDK 17 混装。

- Chat：<https://manzhushaka.cn/gateway/agent-template-pro/chat>
- Console：<https://manzhushaka.cn/gateway/agent-template-pro/console>
- 健康检查：<https://manzhushaka.cn/gateway/agent-template-pro/health>

首次接入或发布链路变更使用候选发布：同一份制品通过远程预检、原子部署和健康检查后才创建最终 Tag 与 GitHub Release。常规发布由新的 `v*` Tag 触发。发布脚本位于 `.release/`，Home Nginx 路由事实源为 `deploy/agent-template-pro.locations.conf`。

## 模块

- `agent-common`：错误模型、脱敏和纯值对象。
- `agent-domain-spi`：领域模块与受控动作扩展契约。
- `agent-runtime`：领域 Agent 注册表、总 Agent 路由与对话生成契约、会话、参数收集、确认、任务状态机与事件协议。
- `agent-infrastructure`：签名访客身份、内存/JDBC 存储和 OpenAI/MiniMax 路由、意图与对话生成适配器。
- `api-chat` / `api-console`：分别面向访客和管理端的入站 API。
- `agent-demo`：酒店、体育、文旅和免税四个公开假数据示例领域。
- `agent-boot`：唯一默认启动模块；包含 MySQL 版 Flyway 建表迁移。

默认运行使用内存存储，便于新 clone 的开发者即刻验证交互；`runtime-jdbc` Profile 提供 MySQL `RuntimeStore`。当前 Console 管理员会话仍保存在进程内，重启后需要重新登录。接入生产时还需配置正式模型或确定性领域规则，并把本地单管理员认证替换为独立管理员认证、共享会话存储与 RBAC。

## API 约定

Chat 使用 `GET /api/chat/v1/bootstrap` 获取公开 Agent 元数据，使用 `POST /api/chat/v1/conversations/{id}/messages:stream` 和 `text/event-stream` 交互，并通过统一时间线恢复消息、路由、卡片和任务。浏览器不提交身份或 Graph thread ID；服务端使用签名 Cookie 解析访客并校验会话归属。高风险动作仅能通过任务 ID、确认版本和用户决定继续执行。

设计和扩展边界见 [智能体开发脚手架系统设计书.md](智能体开发脚手架系统设计书.md)。

## License

This project is licensed under the [MIT License](LICENSE).
