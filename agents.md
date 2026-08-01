回答任何问题前，先说问候语：“你好，我是 manzhushaka，正在为您构建 agent-pro 。ฅ^•ﻌ•^ฅ”

# Agent Template Pro 开发指南

## 1. 适用范围与事实源

本文件适用于仓库根目录及全部子目录。开发、审查和回答仓库问题时，按以下优先级确认事实：

1. 当前源码、`pom.xml`、`package.json`、配置和数据库迁移。
2. `README.md` 与 `docs/领域模块开发指南.md`。
3. `智能体开发脚手架系统设计书.md` 中的目标架构。

设计书包含尚未完全落地的规划。不得把规划中的 Spring AI Alibaba、Redis、MySQL `RuntimeStore`、MyBatis-Plus、RBAC 或完整任务恢复能力描述为当前已实现功能。若代码与文档不一致，应先说明差异，再以当前代码为准；实现行为变化后同步更新相关文档。

除非用户明确指定 superpower，否则不要擅自调用相关 skill。仓库根目录当前没有 `.codegraph/`；不要自行创建索引。若以后出现 `.codegraph/`，理解或定位代码时先使用 CodeGraph，再使用 `rg` 或直接读取文件。

## 2. 项目定位与当前基线

本项目是面向 C 端自然语言业务应用的代码优先 Agent 脚手架，采用单仓库、模块化单体结构。核心原则是：模型负责理解，确定性代码负责校验、鉴权、确认、执行、幂等和状态判断。

当前技术基线：

- JDK 21、Maven 3.9+、Spring Boot 3.5.x、WebFlux。
- Java 根包名为 `com.manzhushaka.agent`。
- Vue 3、TypeScript 严格模式、Vite 6；`ui-console` 使用 Element Plus。
- `agent-boot` 是唯一默认启动模块，默认端口为 `8080`。
- `ui-chat` 和 `ui-console` 是两个独立前端，通过 Vite 将 `/api` 代理到 `http://localhost:8080`。
- 当前 Runtime 使用 `InMemoryRuntimeStore`，路由和参数提取是确定性 Demo 实现。
- `V001__init_core_tables.sql` 已定义 MySQL 核心表，但数据库持久化实现仍需单独完成。

## 3. 模块边界

必须保持以下依赖方向：

```text
api-chat ─────┐
api-console ──┼──> agent-runtime ───> agent-domain-spi ───> agent-common
agent-demo ───┤
              └──> agent-infrastructure ────────────────> agent-runtime
agent-boot ───> api-chat + api-console + agent-demo + agent-infrastructure
```

各模块职责：

- `agent-common`：无 Web 依赖的错误模型、脱敏工具和纯值对象。不得放 Controller、Mapper、领域 DTO 或万能工具类。
- `agent-domain-spi`：`DomainModule`、`AgentAction`、`ActionDescriptor`、`ActionContext`、`ActionResult` 等稳定扩展契约。不得依赖 Web、数据库、Redis 或具体模型供应商。
- `agent-runtime`：会话、参数收集、动作选择、确认门禁、任务状态和事件编排。不得包含具体业务规则，也不得编译期依赖某个领域模块。
- `agent-infrastructure`：身份签名、存储、数据库、缓存、模型、HTTP 客户端和审计等技术实现。领域含义留在领域模块。
- `api-chat`：访客侧入站 API、SSE、请求校验、访客身份和错误映射。不得暴露 Console 能力。
- `api-console`：管理端入站 API、认证、任务与非敏感运行信息。不得复用 Chat Cookie 或代理业务动作。
- `agent-demo`：无真实密钥、客户地址和真实业务数据的公开示例。
- `agent-boot`：应用装配、环境配置、Flyway 迁移和可执行制品，不承载领域逻辑。
- `ui-chat`：C 端会话、补参、确认、卡片和任务状态交互。
- `ui-console`：独立的内部运行控制台，不读取 C 端身份 Cookie。

新增能力应放入拥有该职责的模块。不要通过跨模块 DTO 复用、反向依赖或在 Runtime 中添加 `if (业务类型)` 分支绕过边界。

## 4. 开发工作流

开始修改前：

1. 先执行 `git status --short`，识别并保留用户已有的未提交修改。
2. 先读目标模块的 `pom.xml`、相邻实现和现有测试，再决定实现方式。
3. 使用 `rg` 定位符号和调用点；存在 `.codegraph/` 时优先使用 CodeGraph。
4. 明确改动属于核心协议、领域扩展、基础设施、入站 API 还是 UI，避免顺手重构无关代码。

实现时：

- 优先复用现有 SPI、状态枚举、错误模型和事件协议，不创建平行概念。
- 变更公共契约时，同时检查所有生产者、消费者、序列化格式和测试。
- 不修改无关文件，不覆盖用户的本地配置，不提交生成目录。
- 新依赖必须有明确用途；版本优先由父 POM、Spring BOM 或现有 lockfile 统一管理。
- 完成后检查 diff，并按改动范围执行后端测试、前端构建或接口验证。

## 5. Java 编码规范

- 使用 Java 21 能力，但避免仅为炫技引入复杂语法；纯数据载体优先使用 `record`。
- 使用 4 空格缩进，类、字段、构造器和方法分行书写；新代码不要延续当前 Demo 中多成员挤在同一行的写法。
- 包名全小写；类型使用 `UpperCamelCase`，方法和变量使用 `lowerCamelCase`，常量使用 `UPPER_SNAKE_CASE`。
- 避免通配符导入；按项目约定组织 `java.*`、第三方和项目内导入。不要只为格式化而改动无关旧代码。
- 默认使用构造器注入。Spring Bean 的依赖应为 `private final`，不要新增字段注入。
- 公共方法和 SPI 使用明确类型。`Map<String, Object>` 仅用于当前通用动作边界；稳定或复杂业务输入应定义 DTO，并使用 Bean Validation。
- 集合返回空集合而不是 `null`；不可变输入或快照使用 `List.copyOf`、`Map.copyOf` 等方式保护。
- 时间统一使用 `Instant` 表达服务端时间，数据库和接口需明确时区与序列化格式。
- 业务失败使用 `BusinessException` 和稳定的 `ErrorCode`；不要向前端返回堆栈、内部异常文本或第三方原始报错。
- 注释解释边界、原因和风险，不复述代码。新增公共扩展点时补充简短 Javadoc。
- 不静默吞异常。外部调用异常需区分可重试失败、确定失败和业务结果未知。

## 6. Agent 与领域动作规范

新增领域模块时，实现 `DomainModule` 并通过 Spring 注册 `AgentAction`：

- 动作码采用 `<domain>.<resource>.<verb>`，全仓唯一且发布后保持稳定。
- `QUERY`、`DRAFT` 可直接执行；`COMMIT`、`PAYMENT`、`AFTER_SALE` 必须经过任务和二次确认，不能暴露裸执行入口绕过 Runtime。
- `ActionDescriptor` 必须声明准确的展示名、模式、必填字段和确认标题。
- 执行前由确定性代码重新完成参数、身份、权限、业务状态和幂等校验。模型输出不是授权依据。
- 确认内容应包含操作对象、关键参数、金额或风险信息，并通过 `confirmationVersion` 防止旧确认重放。
- 动作返回内部 `ActionResult`，不得把外部 API 原始响应直接交给模型或前端。
- 写操作超时后视为结果未知。先按外部业务引用或幂等键查单，不得直接重复写入。
- 异步动作返回 `waitingExternalResult=true`，后续状态只能由可靠查询或验签回调推进，不能由模型或前端猜测成功。
- Demo 数据必须是公开假数据；真实领域模块需补齐输入 DTO、适配器、审计、回调验签、超时和重试策略。

## 7. 会话、任务与事件协议

- Chat API 前缀保持 `/api/chat/v1`，Console API 前缀保持 `/api/console/v1`。
- 用户消息使用 `POST` 请求体提交，并通过 `fetch + ReadableStream` 消费 `text/event-stream`；不要改为在 GET URL 中传消息。
- Chat 写请求携带 `X-Client-Request-Id`。服务端生成缺失值，并将 request ID 贯穿事件、任务、幂等和审计链路。
- 每个 `StreamEvent` 必须保留 `type`、`conversationId`、`requestId`、`sequence`、`timestamp` 和 `payload`。
- 事件名沿用当前协议：`message.final`、`form.request`、`action.confirm`、`card.render`、`task.status`。新增事件前先评估前端兼容性。
- 前端依据 `sequence` 排序和去重；不得利用本地计时器或 UI 状态宣布业务执行成功。
- 每次读取会话、PendingAction 或任务，以及每次提交输入和确认，都必须按服务端解析出的 `visitorId` 校验归属。
- 前端传入的 `conversationId`、任务 ID 或未来的 graph thread ID 只用于资源定位，不能作为身份凭据。
- 任务状态变化必须符合 `TaskStatus` 的合法转换。高风险动作不得从 `WAITING_CONFIRMATION` 之外的状态直接执行。

## 8. API 与错误处理

- Controller 只处理 HTTP 协议、校验、身份解析和 DTO 转换；业务编排放在 Runtime，外部系统调用放在适配器。
- 请求 DTO 使用 `jakarta.validation`，不要在 Controller 中堆叠领域校验。
- Chat 与 Console 分别维护入站 DTO 和鉴权边界，不直接互相依赖。
- HTTP 状态码应区分参数错误、未认证、禁止访问、资源不存在和状态冲突；新增错误码时同步异常映射和测试。
- 对外响应只返回稳定、可处理的信息。密钥、签名、内部地址、原始请求报文和错误堆栈不得出现在响应中。
- SSE 变更至少验证事件类型、顺序、ID、断流处理以及前端缓冲区解析。

## 9. 前端编码规范

- 使用 Vue 3 Composition API、`<script setup lang="ts">` 和 TypeScript `strict`；不使用无边界的 `any`。当前遗留 `any` 在触及对应代码时逐步替换为明确接口。
- API 基址继续使用 `VITE_API_BASE`，默认分别为 `/api/chat/v1` 和 `/api/console/v1`；不要在组件中硬编码部署域名。
- Chat 请求必须保留 `credentials: 'include'`，但前端不得读取、生成或伪造访客身份。
- 流式聊天继续使用原生 `fetch`。若引入 Markdown，必须经白名单净化后渲染，禁止直接把模型输出传给 `v-html`。
- 明确处理空态、加载、失败、补参、待确认、等待外部结果、取消和恢复状态；按钮在请求期间应防重复提交。
- `ui-chat` 保持移动端优先，`ui-console` 保持信息密集的运营工具风格；复用现有 CSS 变量、间距和组件风格。
- `ui-console` 使用 Element Plus 和 `@element-plus/icons-vue` 的现有图标，不手绘重复图标。
- 组件变大时拆分 API、类型、状态和视图组件，但不要为很小的 Demo 逻辑提前建立空抽象层。
- 改动 UI 后至少执行对应项目的 `npm run build`，并在桌面和移动视口检查溢出、遮挡、加载态和错误态。

## 10. 数据库与配置

- 后续代码变更若涉及新增数据表或新增字段，必须将新增表的完整字段定义或全部新增字段同步写入 `agent-boot/src/main/resources/db/migration/V001__init_core_tables.sql` 初始化文件，不得只修改 Java 代码、实体模型或其他迁移脚本而遗漏初始化 SQL。
- 数据结构变更只能新增 Flyway 迁移，路径为 `agent-boot/src/main/resources/db/migration`，命名使用 `V<递增版本>__<说明>.sql`。不得修改已经在共享环境执行过的迁移。
- 禁止使用 `ddl-auto=update` 管理生产结构。表和索引设计需同步考虑访客归属、状态查询、幂等唯一键与审计追踪。
- 核心写操作使用状态条件更新或乐观锁；`action_code + idempotency_key` 的唯一性不能仅依赖应用内存。
- 审计事件原则上追加写，不物理覆盖历史。持久化原始敏感内容前必须确认确有必要，并采用加密或脱敏字段。
- `application.yml` 只放结构和非敏感默认值。本地密钥或数据库连接可放在被忽略的 `application-dev.yml` 或环境变量中。
- `VISITOR_COOKIE_SIGNING_KEY` 和 `CONSOLE_ACCESS_KEY` 的仓库默认值仅限本地 Demo。部署时必须替换，且不得写入 Git、前端包、日志或截图。
- 引入 JDBC/Flyway 后，启动和集成测试环境必须提供明确的数据源配置；不要通过关闭安全检查或提交个人配置来让构建通过。

## 11. 安全底线

- 匿名访问仍必须隔离身份。访客 Cookie 由服务端签名并设置 `HttpOnly`、合适的 `Secure`、`SameSite`、Path 和有效期。
- 生产环境不得保留 `secure=false`、示例签名密钥或本地 Console 访问密钥。
- Chat 和 Console 使用独立的认证、Cookie 与 CORS 策略。生产 Console 应接入独立管理员认证和 RBAC。
- 日志与审计采用字段白名单，手机号、证件号、银行卡号、Token、Cookie、私钥和完整外部报文不得明文记录。
- 外部 API 地址来自受控配置白名单；模型和前端都不能提交任意 URL，防止 SSRF。
- 回调必须验签、防重放，并关联既有任务；不能只凭回调正文推进状态。
- 不得提交 `.env`、`application-dev.yml`、日志、数据库导出、真实客户数据或任何凭据。

## 12. 测试与验证

根据改动范围选择最小但充分的验证集：

```bash
# 全部后端模块
mvn test

# agent-boot 及其依赖模块
mvn -pl agent-boot -am test

# 可执行后端制品
mvn -pl agent-boot -am package

# 两个前端分别进行类型检查和生产构建
npm --prefix ui-chat run build
npm --prefix ui-console run build
```

后端启动：

```bash
mvn -f agent-boot/pom.xml spring-boot:run
```

若启用了 JDBC/Flyway，先通过环境变量或本地忽略配置提供 MySQL 连接。前端开发服务分别执行：

```bash
npm --prefix ui-chat run dev
npm --prefix ui-console run dev
```

测试要求：

- Runtime 变更覆盖参数补全、确认版本、合法状态转换、幂等和事件顺序。
- 身份或 API 变更覆盖访客 A 无法访问、提交或确认访客 B 的资源。
- 领域动作覆盖直执、确认后执行、拒绝、外部等待、失败和结果未知。
- 数据库实现覆盖唯一约束、并发状态更新、迁移和重启恢复。
- 修复缺陷时优先添加能复现问题的回归测试。

## 13. 完成标准

提交工作结果前确认：

- 改动位于正确模块，没有打破依赖方向。
- 没有绕过访客归属、确认门禁、幂等或任务状态机。
- API、SSE、数据库或配置契约变更已同步消费者与文档。
- 新增日志、响应、卡片和审计字段不泄露敏感信息。
- 已执行与改动相称的测试或构建，并明确报告未执行项及原因。
- `git diff` 中不包含无关格式化、生成目录、个人配置或用户原有改动。
