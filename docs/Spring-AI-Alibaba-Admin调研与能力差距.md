# Spring AI Alibaba Admin 调研与能力差距

> 调研日期：2026-08-01
> 当前事实源：<https://github.com/alibaba/spring-ai-alibaba/tree/main/spring-ai-alibaba-admin>
> 调研快照：`dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3`（`alibaba/spring-ai-alibaba` 的 `main` HEAD）
> 旧独立仓库：<https://github.com/spring-ai-alibaba/spring-ai-alibaba-admin>（已归档）

## 1. 结论先行

Spring AI Alibaba Admin 已经不是只有 Prompt 调试页的“小控制台”。当前仓库是两个产品阶段的合并体：

1. **Agent 工程控制台**：Prompt、版本、在线流式调试、会话、数据集、评估器、实验、Trace/Span 可观测性和模型配置。
2. **Agent Studio/应用平台**：应用和版本、Assistant/Workflow 编排、可视化节点调试、知识库与切片、插件/工具、MCP、模型供应商、API Key、账号/工作空间、应用发布和 OpenAPI 调用。

官方 README 将第一组能力描述为 Agent 全生命周期；前端路由和后端 Controller 则证明第二组能力已经进入主干代码，而不是只存在于设计文档。见 [README-zh.md](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/README-zh.md#L13-L52)、[前端路由](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/frontend/packages/main/.umirc.ts#L18-L174)。

原独立仓库已经被 GitHub 标记为 `archived=true`，代码迁入仍在维护的 `alibaba/spring-ai-alibaba/spring-ai-alibaba-admin`。本报告的源码链接全部固定到迁移后主仓的本次 HEAD；不能再把旧独立仓库当作可持续依赖源。

对本项目最重要的判断是：**当前仓库并没有真正接入 Spring AI Alibaba，也没有真正的模型调用。** 根 POM 只有 Spring AI BOM，没有 `com.alibaba.cloud.ai` 依赖；Runtime 的路由和参数提取仍是确定性 Demo，存储实现是 `InMemoryRuntimeStore`。因此当前主要缺口不是“再加几个 Console 页面”，而是先完成真实 Agent Runtime 基座，再按产品范围接入 Admin 的工程化能力。

## 2. 官方项目能力地图

### 2.1 Prompt 工程与模型调试

- Prompt 创建、更新、删除、按 key 查询。
- Prompt 版本创建、查询、历史列表；模板列表和模板详情。
- 在线运行 Prompt，返回流式 NDJSON；支持多轮会话读取和删除。
- 模型配置查询、启用模型查询、供应商/参数配置。
- README 明确写了 Nacos Prompt 代理：Agent 通过 `spring-ai-alibaba-agent-nacos` 按 `promptKey` 加载并动态更新 Prompt。

源码入口：[PromptController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/controller/PromptController.java#L28-L181)、[ModelConfigController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/controller/ModelConfigController.java#L21-L50)、[README 集成配置](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/README-zh.md#L108-L186)。

### 2.2 数据集、评估器与实验

- 数据集和数据集版本的创建、查询、更新、删除。
- 数据项的增删改查、分页；支持从 Trace 创建数据项。
- 评估器、评估器版本、评估器模板的管理；评估器在线 Debug。
- 实验创建、查询、结果明细、停止、重启、删除；支持批量评估和结果对比。

源码入口：[DatasetController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/controller/DatasetController.java#L23-L255)、[EvaluatorController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/controller/EvaluatorController.java#L24-L192)、[ExperimentController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/controller/ExperimentController.java#L22-L146)。

### 2.3 可观测性

- Trace 列表和 Trace 详情。
- Span 级分析、服务列表、概览统计。
- 通过 OTLP/集成的采集链路将 Agent 的模型、工具、向量检索等观测数据送入后端；仓库 Docker 方案包含 LoongCollector、Elasticsearch 和 Kibana。
- README 给出了 OTLP tracing endpoint、采样率、Prompt/Completion/VectorStore/tool input-output 观测开关。

源码入口：[ObservabilityController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/controller/ObservabilityController.java#L17-L60)、[中间件编排](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/deploy/docker-compose/docker-compose-service.yaml#L1-L140)。

### 2.4 应用、版本与 Agent/Workflow 编排

- 应用 CRUD、应用版本查询、发布、复制。
- Assistant 应用和 Workflow 应用分别有编辑页面。
- Workflow 支持开始/结束、输入输出、LLM、API、脚本、变量赋值/处理、知识检索、插件、MCP、应用组件、分类器、判断、迭代、并行等节点。
- 控制台支持初始化调试参数、运行整图、读取节点执行过程、暂停后恢复、运行局部图、停止任务和 SSE 流式执行。
- 对外 OpenAPI 支持 Chat completion、Workflow 同步 completion、异步执行、停止和异步结果查询。

源码入口：[AppController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/builder/controller/AppController.java#L50-L244)、[WorkflowController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/builder/controller/WorkflowController.java#L105-L551)、[NodeTypeEnum](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-runtime/src/main/java/com/alibaba/cloud/ai/studio/runtime/domain/workflow/NodeTypeEnum.java)、[ChatController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-openapi/src/main/java/com/alibaba/cloud/ai/studio/controller/ChatController.java#L79-L322)。

### 2.5 知识库与 RAG

- 知识库 CRUD、Embedding provider/model、检索配置和检索测试。
- 文档上传、编辑、删除、批量删除、分页、重新索引。
- 文档切片预览、切片增删改、批量删除、启用/禁用。
- 后端使用 Elasticsearch 向量存储；文档索引通过 RocketMQ 异步任务处理。

源码入口：[KnowledgeBaseController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/builder/controller/KnowledgeBaseController.java#L51-L218)、[DocumentController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/builder/controller/DocumentController.java#L48-L200)、[DocumentChunkController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/builder/controller/DocumentChunkController.java#L47-L207)。

### 2.6 工具、插件与 MCP

- Plugin CRUD；Plugin 下 Tool CRUD、启用/禁用、测试、发布。
- API/OpenAPI 类型工具和输入 Schema 管理。
- MCP Server CRUD、按 code 查询、工具 Debug。
- 应用可以选择知识库、插件、MCP 和组件作为可调用能力。

源码入口：[PluginController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/builder/controller/PluginController.java#L52-L340)、[McpServerController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/builder/controller/McpServerController.java#L54-L166)。

### 2.7 模型供应商、账号与交付

- Provider 和 Model 的 CRUD、启用模型、参数规则、协议查询。
- Account、Workspace、API Key 管理；GitHub OAuth 登录入口。
- Agent Schema 管理。
- Docker Compose/Kubernetes 部署文件涵盖 MySQL、Redis、Elasticsearch、Kibana、Nacos、RocketMQ、LoongCollector。
- 另有 Graph Studio DSL/代码生成与运行相关接口，支持把可视化定义转成代码或运行配置。

源码入口：[ProviderController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/builder/controller/ProviderController.java#L80-L495)、[AccountController](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/builder/controller/AccountController.java#L51-L183)、[部署说明](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/deploy/README.md)。

## 3. 和本项目的事实对照

| 能力 | `agent-template-pro` 当前事实 | 差距判断 |
|---|---|---|
| Spring AI Alibaba | 根 POM 只导入 `org.springframework.ai:spring-ai-bom`，仓库没有 `com.alibaba.cloud.ai` 依赖 | **基础缺口**：尚未接入 SAA Graph/Agent/模型 Starter |
| 模型调用 | `ChatOrchestrator.select/extract` 按字符串选择 Demo 动作；Console 显示 `deterministic demo router` | **基础缺口**：没有 ChatModel、Prompt、工具调用、结构化输出 |
| 会话/任务 | 有 Chat、PendingAction、确认版本、任务状态和事件协议 | 方向正确，但仅 `InMemoryRuntimeStore`，不能重启恢复或多实例运行 |
| 持久化 | 已有 V001 核心表迁移 | **实现缺口**：没有 MySQL RuntimeStore、并发状态更新、查询分页、审计查询 |
| Console | 验证码/登录会话、运行总览、任务列表、非敏感运行配置 | **功能窄**：没有 Prompt、模型、Trace、评估、知识库、工具、应用版本等控制面 |
| Prompt | 没有 Prompt 实体、版本、发布或 Nacos 适配器 | **高优先级缺口** |
| 评估 | 没有数据集、评估器、实验和结果模型 | **高优先级缺口** |
| 可观测性 | 有任务审计协议和 Actuator 依赖 | **高优先级缺口**：没有 OTLP、Trace/Span、Token/Latency/Tool 指标 |
| RAG | 没有知识库、文档、切片或向量存储 | **核心缺口**：已确定建设完整知识库子系统，排在生产 Runtime 和基础控制面之后 |
| Workflow | 旧设计书明确不做可视化工作流编排器；Runtime 也没有 Graph 实现 | 目标范围已扩展为受控 Workflow Studio，但必须在 Runtime、资源版本和评估稳定后建设 |
| Plugin/MCP | 只有 SPI 的 `AgentAction`，没有外部工具目录、Schema、MCP 客户端 | **核心缺口**：已确定建设 MCP Server、Tool 目录、Schema 版本和执行治理 |
| 认证/RBAC | Console 已有独立会话，但仍是单管理员配置 | 应补共享会话、管理员/角色/权限和资源归属；不要复制官方未完成的权限实现 |

当前仓库证据：[根 POM](../pom.xml)、[README](../README.md#L1-L36)、[ChatOrchestrator](../agent-runtime/src/main/java/com/manzhushaka/agent/runtime/chat/ChatOrchestrator.java#L19-L85)、[RuntimeStore](../agent-runtime/src/main/java/com/manzhushaka/agent/runtime/store/RuntimeStore.java#L8-L21)、[InMemoryRuntimeStore](../agent-infrastructure/src/main/java/com/manzhushaka/agent/infrastructure/store/InMemoryRuntimeStore.java#L15-L36)、[ConsoleController](../api-console/src/main/java/com/manzhushaka/agent/consoleapi/controller/ConsoleController.java#L27-L124)、[核心迁移](../agent-boot/src/main/resources/db/migration/V001__init_core_tables.sql#L1-L6)。

## 4. 建议的补齐顺序

### P0：先让 Runtime 真正成为 Spring AI Alibaba Agent Runtime

1. 在父 POM 同时管理 Spring AI Alibaba BOM，并在 `agent-runtime` 接入 Graph/Agent Framework；在 `agent-boot` 通过 profile 选择 DashScope 或 OpenAI-compatible 模型。
2. 把当前 `select/extract` Demo 路由替换为“模型理解 + 受约束结构化意图 + 代码动作白名单”，保留 `ActionDescriptor`、确认门禁和服务端重校验。
3. 实现 `RuntimeStore` 的 MySQL 版本：会话、消息、PendingAction、Task、Confirmation、Audit 全部持久化；用状态条件更新/乐观锁保证确认和幂等。
4. 增加模型健康检查、超时/重试/结果未知处理、任务恢复和 Graph checkpoint；Redis 只能做 checkpoint/锁，MySQL 仍是业务事实源。

### P1：补 Agent 工程闭环

1. Prompt Registry：Prompt、版本、变量 Schema、草稿/发布、回滚、审计；如果采用 Nacos，增加受控的 Nacos adapter，不让前端直接改生产 Prompt。
2. Observability：OTel/Micrometer trace、模型调用/工具调用/检索 span、token/latency/error 指标；Console 提供 trace 列表、详情和按会话/任务关联。
3. Evaluation：数据集/版本/数据项、评估器模板/版本、实验任务/结果；先做固定回归集验证路由、字段提取、确认和拒绝越权。
4. Console 运行控制面：任务筛选/分页/详情、审计查询、模型状态、Prompt 发布状态、存储/Redis/数据库健康。

### P2：建设 MCP 和知识库核心控制面

- MCP 子系统建设 Server CRUD、连接测试、能力发现、Tool Schema 版本、启停、引用关系、Debug 和执行审计；所有写 Tool 继续受确认和幂等门禁约束。
- 知识库子系统建设 Knowledge Base、文档上传、解析、切片预览/编辑、Embedding、向量检索、异步索引、补偿和检索测试，并保留 citation 和资源版本。
- MCP 和知识库都是必建模块，不再作为业务可选项；二者在基础控制面完成后可并行实施。

### P3：应用发布与受控 Workflow Studio

- 建设 Agent App/Version、资源绑定、发布、回滚、API Key 和 OpenAPI，运行时固定模型、Prompt、Tool/MCP 和知识库版本。
- 可视化 Workflow 已纳入目标范围，但排在 Runtime、MCP、知识库、应用版本和评估之后；节点统一委托现有 Runtime，不建立绕过业务门禁的第二套执行链。

## 5. 成熟度与安全风险

功能数量多不等于可以直接作为生产基线。迁移后的当前源码仍有以下明显风险：

1. Console 拦截器只覆盖 `/console/v1/**`，API Key 拦截器只覆盖 `/api/v1/**`；Prompt、数据集、评估器、实验和 Observability 使用另一组 `/api/**` 路径，不能从这份拦截器配置证明它们已被统一鉴权。[InterceptorConfig](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/builder/interceptor/InterceptorConfig.java#L41-L51)
2. 当前 CORS 允许任意 origin pattern 且允许 credentials；Token 拦截器还接受 URL query 参数中的 access token。生产系统不应复制这两个默认行为。[WebConfig](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/config/WebConfig.java#L26-L34)、[TokenAuthInterceptor](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/builder/interceptor/TokenAuthInterceptor.java#L72-L88)
3. GitHub OAuth callback 把 access token 和 refresh token 放入重定向 URL；模型凭据的 RSA 解密私钥从 classpath 资源加载。它们都不符合本项目的生产密钥边界。[Oauth2Controller](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/builder/controller/Oauth2Controller.java#L47-L56)、[RSACryptUtils](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-core/src/main/java/com/alibaba/cloud/ai/studio/core/utils/security/RSACryptUtils.java#L44-L56)
4. Workspace 权限检查仍留有 TODO；Nacos Prompt 发布失败只记日志，可能形成数据库版本与实际下发状态不一致；Tracing 的 `errorCount` 仍硬编码为 0。[权限 TODO](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/builder/aspect/AuthLoggingAspect.java#L68-L77)、[PromptVersionServiceImpl](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/service/impl/PromptVersionServiceImpl.java#L121-L141)、[TracingRepositoryImpl](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/repository/impl/TracingRepositoryImpl.java#L151-L168)
5. Prompt 调试会话保存在进程内 `ConcurrentHashMap`，30 分钟过期；实验任务由进程内固定线程池执行。这两条路径都不具备可证明的重启恢复能力，不能直接作为本项目的持久化任务方案。[ChatSessionServiceImpl](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/service/impl/ChatSessionServiceImpl.java#L26-L42)、[ExperimentServiceImpl](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/service/impl/ExperimentServiceImpl.java#L58-L62)
6. 当前目录约有 684 个 `src/main` Java 文件，只有 8 个 Java 测试类，未看到前端测试文件，也未看到覆盖 Controller、Prompt、Workflow 和 Evaluation 主链的系统测试。应把它视为功能丰富的工程样板，而不是“功能均已生产验证”的证据。

## 6. 不应直接照搬的部分

1. **版本基线不能直接复制**：官方快照使用 Java 17、Spring Boot 3.3.6、Spring AI 1.1.2、Spring AI Alibaba 1.0.0.3；本项目基线是 JDK 21、Spring Boot 3.5.x，依赖应先做兼容性验证。[官方 POM](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/pom.xml#L41-L55)
2. **官方仓库并非所有模块都已收敛**：部分 DSL adapter 仍有 `UnsupportedOperationException`/TODO。它适合作为能力和接口参考，不能当作本项目的安全基线或“全部功能已生产验证”的证明。[DSL adapter](https://github.com/alibaba/spring-ai-alibaba/blob/dc6fcadca81ed48e0c7a8ed35920ecb06c26f0e3/spring-ai-alibaba-admin/spring-ai-alibaba-admin-server-start/src/main/java/com/alibaba/cloud/ai/studio/admin/builder/generator/service/dsl/adapters/StudioDSLAdapter.java#L70-L85)
3. **官方 Studio 与本项目定位不同**：Studio 是应用平台/低代码编排方向；本项目的设计书明确选择代码优先、领域模块版本化、模型不能决定授权和业务结果。应复用底层协议思想，不直接把任意工具、Prompt、URL 或业务动作开放给后台配置。
4. **官方部署依赖更重**：MySQL、Redis、Elasticsearch、RocketMQ、Nacos、LoongCollector/Kibana 需要单独运维。应按 P0 至 P3 分阶段引入，不要为了“功能看起来齐全”一次性把所有中间件塞进默认启动链路。

## 7. 本次调研后的产品决策

产品范围已经明确：不在“代码优先 Runtime”和“Agent Studio”之间二选一，而是分阶段建设一个仍由确定性代码控制高风险动作的 Agent 开发与治理平台：

- 首先完成真实 SAA Agent/Graph、MySQL/Redis 恢复、安全基座、模型和 Prompt 管理。
- 随后把 MCP 管理和知识库管理作为必建核心模块并行交付。
- 再建设 Agent App/Version、发布 OpenAPI、Trace 和评估闭环。
- 最后交付受控 Workflow Studio，并继续保留 C 端 Chat、内部 Console、访客归属、确认、幂等和任务状态机边界。

下一步应从 P0 的依赖和 Runtime 兼容性验证开始，再按完整计划中的里程碑转成有测试、有迁移、有 API 契约的纵向切片；不直接从官方前端页面反推“复制一套 Admin”作为实现方案。

完整落地步骤见：[Agent Template Pro 完整能力实施计划](./Agent-Pro完整能力实施计划.md)。
