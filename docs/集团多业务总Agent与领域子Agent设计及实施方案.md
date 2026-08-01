# 集团多业务总 Agent 与领域子 Agent 设计及实施方案

> 文档状态：第一批交付已实施
> 适用项目：`agent-template-pro`
> 编制日期：2026-08-01
> 目标读者：架构、后端、前端、测试、产品与集团客户项目实施团队

## 1. 结论先行

集团客户通常同时经营酒店、体育、文旅、免税、零售、交通等业务。面向普通 C 端消费者时，不应要求用户先理解集团组织结构、选择系统或寻找业务入口。消费者只面对一个统一的总 Agent，由总 Agent 理解问题、选择领域子 Agent，并由子 Agent 在自己的业务边界内完成问答、补参、确认、执行和结果展示。

本项目应采用以下基线：

1. **一个统一入口**：C 端始终面对集团总 Agent，不暴露多个割裂的聊天入口。
2. **多个代码优先的领域子 Agent**：每个 `DomainModule` 对应一个可注册、可识别、可审计的领域 Agent，例如酒店 Agent、体育 Agent、文旅 Agent、免税 Agent。
3. **两级路由**：总 Agent 先选领域，领域 Agent 再从自己的动作白名单中选择 `AgentAction`。
4. **确定性执行边界不变**：模型只负责理解、领域选择、动作候选、参数提取和解释；鉴权、校验、确认、幂等、状态转换和外部调用仍由代码控制。
5. **路由过程可恢复、可审计、可展示**：每次路由都必须关联 `conversationId`、`requestId`、目标 Agent 和动作，不能只存在于模型上下文中。
6. **前端真实呈现**：只有后端完成路由并返回 Agent 元数据后，前端才显示“已转接至酒店服务 Agent”；不能仅根据按钮或关键词在浏览器内猜测。
7. **第一阶段不做通用多 Agent 自由协商**：先实现单轮单领域接管和跨轮切换。跨领域复合交易后续通过受控 Workflow 实现，避免多个子 Agent 并行触发不可逆写操作。

该方案不是另建一套 Runtime，而是把现有 `DomainModule + AgentAction + ChatOrchestrator + StreamEvent` 演进为具备领域归属的多 Agent Runtime。

## 2. 业务背景与目标

### 2.1 典型集团业务版图

```text
集团统一服务入口
├── 酒店业务
│   ├── 酒店查询
│   ├── 房态与报价
│   ├── 预订与取消
│   └── 会员权益
├── 体育业务
│   ├── 场馆查询
│   ├── 赛事与活动
│   ├── 场地预约
│   └── 票务与核销
├── 文旅业务
│   ├── 景区问答
│   ├── 路线推荐
│   ├── 门票与活动
│   └── 现场服务
└── 免税业务
    ├── 商品查询
    ├── 库存与价格
    ├── 购买资格
    └── 提货与售后
```

用户可能从任意问题进入，例如：

- “明晚还有海景房吗？”
- “周末体育馆有哪些比赛？”
- “带老人去哪个景区比较合适？”
- “离岛免税的香水怎么提货？”
- “先订酒店，再帮我看看附近有什么活动。”

系统必须先识别业务领域，再进入该领域的受控动作链路。消费者不需要事先选择“酒店系统”或“文旅系统”。

### 2.2 产品目标

- 对消费者提供统一、连续、低认知成本的自然语言入口。
- 对集团保留清晰的业务边界、系统边界、权限边界和责任归属。
- 新增业务版图时主要增加领域模块，不修改核心 Runtime 中的业务分支。
- 支持每个子 Agent 使用不同的 Prompt、知识库、工具、外部 API 和展示卡片。
- 支持按领域统计路由量、转接率、确认率、成功率、失败率和人工接管率。
- 支持未来接入 Agent Application、知识库、MCP 和 Workflow，但不依赖这些后续能力才能启动第一阶段。

### 2.3 非目标

- 第一阶段不建设允许 Agent 任意发现、调用和互相对话的开放式多 Agent 网络。
- 第一阶段不允许子 Agent 绕过 Runtime 直接调用另一个子 Agent 的写动作。
- 第一阶段不处理一个请求内多个领域写动作的并行事务。
- 不把本项目扩展成多租户计费 SaaS；当前仍以一个集团项目一次独立部署为默认交付方式。
- 不把模型的路由理由、思维链或内部 Prompt 原文返回给前端。

## 3. 当前代码事实

### 3.1 当前请求链路

```mermaid
sequenceDiagram
    participant U as C 端用户
    participant UI as ui-chat
    participant API as ChatController
    participant RT as ChatOrchestrator
    participant IR as IntentResolver
    participant ACT as AgentAction
    participant STORE as RuntimeStore

    U->>UI: 输入自然语言
    UI->>API: POST messages:stream
    API->>RT: message(visitorId, conversationId, content, requestId)
    RT->>STORE: 记录 USER 消息
    RT->>IR: 全部 ActionDescriptor + 用户内容
    IR-->>RT: actionCode + input
    RT->>RT: 必填字段、动作模式、确认门禁
    RT->>ACT: execute(ActionContext, input)
    ACT-->>RT: ActionResult
    RT->>STORE: 消息、事件、任务与审计
    RT-->>API: StreamEvent 列表
    API-->>UI: SSE
```

当前实现具备以下基础：

| 能力 | 当前实现 | 事实位置 |
| --- | --- | --- |
| 领域扩展 | `DomainModule` 提供 `code`、`displayName` 和 `actions` | `agent-domain-spi/.../DomainModule.java` |
| 动作协议 | `ActionDescriptor` 声明动作码、模式、必填字段和确认标题 | `agent-domain-spi/.../ActionDescriptor.java` |
| 意图解析 | `IntentResolver` 从全部动作中选择一个动作并提取参数 | `agent-runtime/.../intent` |
| 模型适配 | OpenAI 与 MiniMax 负责领域路由、`actionCode + input` 和总 Agent 自然语言回复，失败后回退确定性解析或预设回复 | `agent-infrastructure/.../model` |
| 编排 | `ChatOrchestrator` 处理补参、确认、执行、任务和事件 | `agent-runtime/.../ChatOrchestrator.java` |
| 身份隔离 | Chat API 通过服务端签名 Cookie 解析 `visitorId` | `api-chat/.../ChatController.java` |
| 持久化 | 内存和 JDBC 两种 `RuntimeStore` | `agent-infrastructure/.../store` |
| 前端协议 | Vue 读取 SSE，展示消息、表单、确认、卡片和任务 | `ui-chat/src/App.vue` |

### 3.2 当前并不是真正的总 Agent 与子 Agent

当前 `ChatOrchestrator` 在构造时执行：

```java
modules.forEach(module -> module.actions().forEach(
        action -> actions.put(action.descriptor().code(), action)));
```

该过程将所有领域动作压平为一个 `actionCode -> AgentAction` Map，随后丢失以下信息：

- 动作属于哪个 `DomainModule`。
- 本轮由哪个领域 Agent 接管。
- 总 Agent 为什么选择该领域的受控公开原因码。
- 领域 Agent 是否启用、是否可在 C 端展示。
- 子 Agent 的名称、描述、图标和推荐问题。
- 子 Agent 专属 Prompt、知识、模型或工具边界。

当前 `IntentDecision` 只包含 `actionCode` 和 `input`。`message.final` 的 payload 只包含 `content`。`ChatMessage` 也没有 `agentCode` 和 `actionCode`。因此前端无法从服务端事实中判断回复来自哪个子 Agent。

### 3.3 当前 Demo 也只有一个领域模块

`agent-demo` 当前只注册一个 `demo` 模块，天气、预约和配送是同一模块下的三个动作。它们是三个能力，不是三个子 Agent。若前端直接显示为“天气 Agent、预约 Agent、配送 Agent”，会把 UI 概念描述成尚未实现的运行事实。

### 3.4 当前设计书已经预留正确方向

现有设计书已经提出：

- 新增 `travel-agent`、`hotel-agent`、`logistics-agent` 等领域模块。
- `DomainModule` 表示完整业务领域。
- Graph 中存在 `route_domain` 节点。
- Runtime 只负责通用编排，不写具体领域规则。

本方案负责把上述目标细化为兼容当前代码的协议、持久化和前端实现。

## 4. 核心术语与边界

| 概念 | 中文名称 | 职责 | 不负责 |
| --- | --- | --- | --- |
| `CoordinatorAgent` | 总 Agent / 协调 Agent | 统一入口、识别领域、处理澄清、选择目标子 Agent | 不执行具体领域写动作，不替代领域校验 |
| `DomainAgent` | 领域子 Agent | 在单一业务版图内理解请求、选择动作、补充领域信息、解释结果 | 不越权调用其他领域动作 |
| `DomainModule` | 领域模块 | 子 Agent 的代码注册单元，提供描述和动作集合 | 不承载 Runtime 状态机 |
| `AgentAction` | 受控动作 | 执行确定性的查询、草稿、提交、支付或售后操作 | 不直接相信模型或前端输入 |
| `DomainAgentRegistry` | 子 Agent 注册表 | 保存 Agent 描述、动作所有权并校验唯一性 | 不从数据库动态加载任意 Java 代码 |
| `RouteDecision` | 路由决定 | 记录总 Agent 选中的目标领域和路由来源 | 不作为授权依据 |
| `PendingAction` | 待补充动作 | 锁定领域、动作、已收集参数和过期时间 | 不允许无提示地切换领域 |
| `AgentTask` | 业务任务 | 保存写动作、确认、幂等和状态 | 不表示外部订单的全部领域状态 |

### 4.1 业务领域与组织架构不是一回事

子 Agent 应按稳定业务能力划分，而不是机械复制集团部门树。例如“酒店预订”可以是一个领域 Agent，但“集团办公室”“海南分公司”通常不是消费者业务领域。组织归属可进入配置和审计，不应成为 Runtime 的业务分支。

### 4.2 子 Agent 与动作不是一回事

酒店 Agent 可以拥有多个动作：

```text
hotel.room.search
hotel.quote.create
hotel.booking.create
hotel.booking.query
hotel.booking.cancel
```

模型只能在酒店 Agent 已注册的动作集合中选择。动作码继续采用 `<domain>.<resource>.<verb>`，其首段必须与所属领域 Agent 的 `code` 一致。

## 5. 目标架构

```mermaid
flowchart LR
    U["C 端消费者"] --> UI["统一 Chat UI"]
    UI --> API["api-chat"]
    API --> CO["Coordinator Agent"]

    CO --> REG["DomainAgentRegistry"]
    CO --> HR["Hotel Agent"]
    CO --> SR["Sports Agent"]
    CO --> TR["Tourism Agent"]
    CO --> DR["Duty-Free Agent"]

    HR --> HA["Hotel Actions"]
    SR --> SA["Sports Actions"]
    TR --> TA["Tourism Actions"]
    DR --> DA["Duty-Free Actions"]

    HA --> GATE["Runtime Policy Gate"]
    SA --> GATE
    TA --> GATE
    DA --> GATE

    GATE --> TASK["Pending / Confirm / Task"]
    TASK --> ADAPTER["受控业务适配器"]
    TASK --> EVENT["SSE / Audit / Outbox"]
```

### 5.1 模块依赖保持不变

```text
api-chat -> agent-runtime -> agent-domain-spi -> agent-common
agent-infrastructure -> agent-runtime
具体领域模块 -> agent-domain-spi
agent-boot -> api-chat + agent-infrastructure + 选定领域模块
```

新增的注册表、路由状态和子 Agent 编排属于 `agent-runtime`。领域描述契约属于 `agent-domain-spi`。模型路由适配器和持久化实现属于 `agent-infrastructure`。不得在 Runtime 中增加 `if (hotel)`、`if (tourism)` 等业务分支。

### 5.2 代码优先与配置边界

第一阶段中：

- Agent 的动作实现、输入校验、风险模式和外部适配器由代码定义。
- Agent 的公开名称、简介、图标键、推荐问题和路由提示由 `DomainModule` 描述。
- 模型密钥和运行 Profile 由受控配置提供。
- Console 只读展示运行时实际注册的 Agent，不允许在线注入任意类、URL 或工具。

后续 Agent Application 发布能力成熟后，可以把 Prompt、知识库、MCP 和模型绑定版本化，但仍不能绕过代码注册的动作白名单。

## 6. 两级路由设计

### 6.1 标准单轮流程

```mermaid
sequenceDiagram
    participant U as 用户
    participant C as Coordinator Agent
    participant R as DomainAgentRegistry
    participant D as Domain Agent
    participant P as Policy Gate
    participant A as AgentAction
    participant S as RuntimeStore

    U->>C: 自然语言请求
    C->>S: 加载会话、当前 Agent、Pending 和任务
    C->>R: 获取当前启用的领域 Agent 描述
    C->>C: 选择目标领域
    C->>S: 保存 RouteDecision
    C-->>U: agent.route
    C->>D: 用户请求 + 受控会话上下文
    D->>D: 只从本领域动作中选择 actionCode 并提取 input
    D->>P: actionCode + input + visitor context
    P->>P: 归属、Schema、权限、风险和幂等校验
    alt 缺少参数
        P-->>U: form.request
    else 需要确认
        P-->>U: action.confirm
    else 可执行
        P->>A: execute
        A-->>P: ActionResult
        P->>S: 保存消息、事件、任务与审计
        P-->>U: message.final + card.render + task.status
    end
```

### 6.2 路由优先级

路由必须按以下顺序执行：

1. **任务或补参锁定**：若请求通过 `/pending-actions/{id}/input` 或 `/tasks/{id}/confirm` 进入，直接使用记录中的 `domainCode`，禁止重新让模型选择领域。
2. **显式切换指令**：用户明确说“切换到酒店服务”“我想问免税商品”时，允许切换领域。
3. **当前会话上下文**：用户使用“这个房型”“刚才的门票”等指代表达时，优先沿用当前 Agent，但仍需校验上下文可解释性。
4. **总 Agent 路由**：从启用且对当前应用可见的 Agent 中选择目标。
5. **低置信度澄清**：低于阈值时返回受控澄清选项，不得随意选第一个 Agent。
6. **不支持请求**：返回统一说明或人工服务入口，不伪造领域能力。

### 6.3 路由结果类型

```java
public enum RouteType {
    DOMAIN_AGENT,
    KEEP_CURRENT_AGENT,
    CLARIFICATION_REQUIRED,
    GENERAL_ASSISTANCE,
    UNSUPPORTED
}
```

- `DOMAIN_AGENT`：切换到新的领域 Agent。
- `KEEP_CURRENT_AGENT`：继续由当前领域 Agent 处理。
- `CLARIFICATION_REQUIRED`：需要用户从有限候选领域中确认。
- `GENERAL_ASSISTANCE`：总 Agent 可以直接回答的集团公共问题，例如客服电话、隐私说明。
- `UNSUPPORTED`：当前应用未注册可处理该请求的 Agent。

### 6.4 跨领域请求

第一阶段采用“拆分并顺序处理”：

```text
用户：帮我订酒店，再买两张景区票。

总 Agent：这个请求包含酒店预订和文旅购票两个业务。
          我先为你处理酒店，完成后再继续景区门票，可以吗？
```

原则：

- 一次只允许一个领域 Agent 拥有当前写操作。
- 当前写任务进入 `WAITING_CONFIRMATION` 后，不得被另一领域请求隐式覆盖。
- 查询类请求可以在不破坏 PendingAction 的前提下临时路由，但 UI 必须保留待处理任务入口。
- 真正的跨领域套餐、联合订单和补偿事务应由后续受控 Workflow 实现。

### 6.5 路由失败与降级

- 模型不可用时使用确定性路由器；确定性路由器只基于已注册 Agent 的路由提示和稳定规则。
- 模型返回不存在的 `agentCode` 时拒绝该结果并回退，不允许动态访问任意 Agent。
- 目标 Agent 已禁用时返回可处理的错误，不静默换到相似 Agent。
- 路由成功但动作识别失败时，由目标 Agent 发起领域内澄清，不把全部领域动作重新暴露给模型。
- 路由和动作解析必须分别记录来源：`MODEL`、`DETERMINISTIC_FALLBACK`、`CURRENT_CONTEXT`、`USER_SELECTED`。

## 7. SPI 与 Runtime 契约设计

### 7.1 兼容扩展 `DomainModule`

保留现有三个方法，新增默认描述方法，避免一次性破坏已有领域模块：

```java
public interface DomainModule {
    String code();
    String displayName();
    Collection<AgentAction> actions();

    default DomainAgentDescriptor agentDescriptor() {
        return DomainAgentDescriptor.basic(code(), displayName());
    }
}
```

新增描述类型：

```java
public record DomainAgentDescriptor(
        String code,
        String displayName,
        String description,
        String iconKey,
        String routingDescription,
        List<String> routingHints,
        List<SuggestedPromptDescriptor> suggestedPrompts,
        int displayOrder,
        boolean visibleToVisitor
) {
    public DomainAgentDescriptor {
        routingHints = List.copyOf(routingHints);
        suggestedPrompts = List.copyOf(suggestedPrompts);
    }

    public static DomainAgentDescriptor basic(String code, String displayName) {
        return new DomainAgentDescriptor(
                code, displayName, "", "bot", "", List.of(), List.of(), 100, false);
    }
}
```

`iconKey` 必须来自前端白名单，例如 `hotel`、`ticket`、`landmark`、`shopping-bag`，不能接受任意 SVG 或 URL。`routingDescription` 给总 Agent 使用，`description` 给消费者展示，两者不可混用。兼容默认描述采用 `visibleToVisitor=false`，已有模块不会因为升级依赖而自动暴露到 C 端；准备公开的模块必须显式覆盖完整描述。

### 7.2 新增 `DomainAgentRegistry`

`agent-runtime` 在启动时构建不可变注册表：

```java
public interface DomainAgentRegistry {
    List<RegisteredDomainAgent> enabledAgents();
    Optional<RegisteredDomainAgent> findAgent(String agentCode);
    Optional<OwnedAction> findAction(String actionCode);
}

public record RegisteredDomainAgent(
        DomainAgentDescriptor descriptor,
        Map<String, AgentAction> actions
) { }

public record OwnedAction(
        String agentCode,
        AgentAction action
) { }
```

启动校验必须失败快速：

- Agent code 全局唯一。
- Action code 全局唯一。
- Action code 首段等于 Agent code。
- 一个 Action 只能属于一个 Agent。
- C 端展示的 Agent 必须提供非空展示名称和受支持的 `iconKey`。
- 高风险动作必须具有确认标题。
- 注册表对外只暴露不可变快照。

### 7.3 总 Agent 路由契约

```java
public interface CoordinatorRouter {
    RouteDecision route(
            String content,
            ConversationRoutingContext context,
            List<DomainAgentDescriptor> candidates);
}

public record RouteDecision(
        RouteType type,
        String targetAgentCode,
        double confidence,
        RouteSource source,
        String reasonCode,
        List<String> clarificationCandidates
) { }
```

`reasonCode` 是受控枚举值，例如 `EXPLICIT_HOTEL_INTENT`、`CURRENT_AGENT_CONTEXT`、`LOW_CONFIDENCE`，用于审计和指标。前端只显示预先映射的公开文案，不显示模型自由生成的路由理由。

### 7.4 领域 Agent 动作解析

现有 `IntentResolver` 可以继续使用，但调用范围必须收窄为目标 Agent 的动作：

```java
IntentDecision decision = intentResolver.resolve(
        content,
        targetAgent.actions().values().stream()
                .map(AgentAction::descriptor)
                .toList());
```

Runtime 随后必须再次校验：

```java
OwnedAction ownedAction = registry.findAction(decision.actionCode()).orElseThrow(...);
if (!ownedAction.agentCode().equals(route.targetAgentCode())) {
    throw new BusinessException(ErrorCode.AGENT_ACTION_OWNERSHIP_INVALID, ...);
}
```

即使模型输出了另一个领域的合法动作码，也不能越过当前 Agent 的动作白名单。

### 7.5 `ChatOrchestrator` 的演进方式

保留 `ChatOrchestrator` 作为 API 使用的统一门面，内部拆出：

```text
ChatOrchestrator
├── ConversationContextLoader
├── CoordinatorRoutingService
├── CoordinatorResponseService
├── DomainAgentRegistry
├── DomainIntentService
├── ActionPolicyGate
├── DomainActionExecutor
└── RuntimeEventFactory
```

不建议把全部逻辑继续堆进当前单类，也不建议让 Controller 直接调用 Router 或 Action。

`GENERAL_ASSISTANCE` 由 `CoordinatorResponseService` 生成总 Agent 回复。第一阶段只允许回答配置中的集团公共信息或返回受控兜底文案，不得借此执行领域动作。`CLARIFICATION_REQUIRED` 和 `UNSUPPORTED` 同样由 Runtime 生成受控文本与候选项，不进入任意业务适配器。

### 7.6 会话路由上下文

```java
public record ConversationRoutingContext(
        String conversationId,
        String currentAgentCode,
        long routingVersion,
        Optional<PendingActionSummary> pendingAction,
        List<RecentAgentTurn> recentTurns
) { }
```

只传递完成路由所需的脱敏摘要。不得把历史完整敏感消息、Cookie、Token 或外部原始报文拼入模型 Prompt。

## 8. 事件与 API 设计

### 8.1 保持 `StreamEvent` 顶层结构

继续保留：

```json
{
  "type": "agent.route",
  "conversationId": "cnv_xxx",
  "requestId": "req_xxx",
  "sequence": 12,
  "timestamp": "2026-08-01T12:00:00Z",
  "payload": {}
}
```

不修改顶层字段，避免破坏 SSE 排序、去重和恢复逻辑。

### 8.2 新增 `agent.route` 事件

```json
{
  "type": "agent.route",
  "payload": {
    "coordinatorCode": "group-assistant",
    "targetAgentCode": "hotel",
    "targetAgentName": "酒店服务",
    "routeType": "DOMAIN_AGENT",
    "routeSource": "MODEL",
    "reasonCode": "HOTEL_BOOKING_INTENT"
  }
}
```

旧前端会忽略未知事件，因此新增事件本身向后兼容。新前端根据 `sequence` 展示转接提示并恢复当前 Agent。

当 `routeType=CLARIFICATION_REQUIRED` 时，payload 增加公开候选 Agent 列表。用户选择后调用服务端显式选择接口，前端不能只修改本地当前 Agent：

```http
POST /api/chat/v1/conversations/{conversationId}/agent:select
X-Client-Request-Id: req_xxx
Content-Type: application/json

{
  "targetAgentCode": "hotel",
  "expectedRoutingVersion": 3
}
```

服务端必须校验访客归属、路由版本、Agent 可见性以及该 Agent 是否属于最近一次澄清候选，然后返回 SSE `agent.route`。侧栏中的显式 Agent 选择也复用该接口。

### 8.3 扩展现有事件 payload

所有领域事件增加可选元数据：

```json
{
  "agent": {
    "code": "hotel",
    "name": "酒店服务"
  },
  "actionCode": "hotel.room.search"
}
```

示例 `message.final`：

```json
{
  "type": "message.final",
  "payload": {
    "content": "已经为你找到 3 个可预订房型。",
    "agent": { "code": "hotel", "name": "酒店服务" },
    "actionCode": "hotel.room.search"
  }
}
```

`form.request`、`action.confirm`、`card.render` 和 `task.status` 同样携带 Agent 归属，保证断流恢复后 UI 不会把酒店卡片标成总 Agent 的通用结果。

### 8.4 Chat Bootstrap API

新增：

```http
GET /api/chat/v1/bootstrap
```

响应：

```json
{
  "application": {
    "code": "group-consumer-service",
    "displayName": "集团智慧服务"
  },
  "coordinator": {
    "code": "group-assistant",
    "displayName": "集团总智能体",
    "description": "统一理解需求并协调专业服务"
  },
  "agents": [
    {
      "code": "hotel",
      "displayName": "酒店服务",
      "description": "房态、报价、预订和订单服务",
      "iconKey": "hotel",
      "suggestedPrompts": [
        { "title": "查询房态", "prompt": "明晚还有海景房吗？" }
      ]
    }
  ]
}
```

该接口只返回公开白名单字段，不返回 Prompt、路由提示、模型配置、内部地址、动作实现或密钥状态。

### 8.5 统一时间线与历史消息 DTO

当前 Controller 直接返回 Runtime 的 `ChatMessage`。实施时应改为 `ChatMessageResponse`，至少包含：

```text
sequence
role
content
eventType
agentCode
agentName
actionCode
createdAt
```

`ConversationResponse` 增加可选 `activeAgentCode`、`activeAgentName` 和 `routingVersion`。旧数据为空时按总 Agent 展示。

仅调用当前 `/messages` 再从“最后一条普通消息之后”恢复事件，会遗漏早于最后一条回复的 `agent.route`，也无法稳定恢复之前轮次的卡片。建议新增统一时间线：

```http
GET /api/chat/v1/conversations/{conversationId}/timeline?afterSequence=0&limit=200
```

`TimelineItemResponse` 按 sequence 合并用户消息、助手消息和可展示系统事件，并对相同 sequence 的消息与 StreamEvent 去重。前端首次打开会话读取时间线，断流后继续使用现有 `/events?afterSequence=` 增量恢复。这样转接、表单、确认、卡片和任务在刷新后仍能按原顺序重建。

### 8.6 错误码

建议新增稳定错误码：

| 错误码 | HTTP | 含义 |
| --- | --- | --- |
| `AGENT_NOT_FOUND` | 404 | 目标 Agent 未注册或不可见 |
| `AGENT_DISABLED` | 409 | 目标 Agent 当前不可用 |
| `AGENT_ROUTE_AMBIGUOUS` | 409 | 需要用户确认目标领域 |
| `AGENT_ACTION_OWNERSHIP_INVALID` | 422 | 动作不属于当前 Agent |
| `AGENT_SWITCH_BLOCKED_BY_PENDING_ACTION` | 409 | 存在待补参或待确认写动作 |

错误响应不得回显模型原始输出或内部 Prompt。

## 9. 持久化设计

### 9.1 设计原则

- Agent 注册定义继续以代码为事实源，不在第一阶段增加 Agent CRUD 表。
- 路由决定必须追加保存，不能只修改会话当前 Agent。
- 会话保存当前 Agent 快照以便快速恢复；路由历史用于审计和分析。
- 任务、PendingAction、消息、事件和审计都保存 Agent 归属。
- 所有结构变更同时更新 `V001__init_core_tables.sql` 和新增 Flyway 增量迁移。

### 9.2 新增路由决定表

逻辑表结构：

```sql
CREATE TABLE agent_route_decision (
    id VARCHAR(64) PRIMARY KEY,
    visitor_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    route_sequence INT NOT NULL DEFAULT 1,
    source_agent_code VARCHAR(128) NOT NULL,
    target_agent_code VARCHAR(128) NULL,
    route_type VARCHAR(32) NOT NULL,
    route_source VARCHAR(32) NOT NULL,
    confidence DECIMAL(5, 4) NULL,
    reason_code VARCHAR(64) NOT NULL,
    candidate_agents_json JSON NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_route_request_sequence
        (conversation_id, request_id, route_sequence),
    INDEX idx_route_target_created
        (target_agent_code, created_at),
    INDEX idx_route_conversation_created
        (conversation_id, created_at)
);
```

`candidate_agents_json` 只保存 Agent code，不保存模型完整响应。

### 9.3 现有表新增字段

| 表 | 新增字段 | 用途 |
| --- | --- | --- |
| `agent_conversation` | `active_agent_code`, `routing_version` | 当前 Agent 快照和乐观更新版本 |
| `agent_message` | `agent_code`, `action_code` | 历史消息归属 |
| `agent_pending_action` | `domain_code` | 补参过程锁定领域 |
| `agent_stream_event` | `agent_code`, `action_code` | 领域查询和可观测索引 |
| `agent_audit_event` | `request_id`, `domain_code`, `action_code` | 完整审计关联 |

`agent_task` 已有 `domain_code`，但当前 JDBC 通过截取 `actionCode` 首段临时推导。实施后应直接使用注册表返回的 Agent code，移除字符串猜测作为主路径；历史兼容读取可以保留前缀回退。

### 9.4 `RuntimeStore` 契约演进

避免继续增加长参数列表，新增值对象：

```java
public record MessageAppend(
        String conversationId,
        String role,
        String content,
        String eventType,
        String agentCode,
        String actionCode
) { }

public record RouteDecisionRecord(
        String id,
        String visitorId,
        String conversationId,
        String requestId,
        int routeSequence,
        String sourceAgentCode,
        String targetAgentCode,
        RouteType routeType,
        RouteSource routeSource,
        Double confidence,
        String reasonCode,
        List<String> candidateAgentCodes,
        Instant createdAt
) { }
```

`RuntimeStore` 新增：

```java
long appendMessage(MessageAppend message);
void saveRouteDecision(RouteDecisionRecord decision);
Optional<RouteDecisionRecord> latestRoute(String visitorId, String conversationId);
Optional<PendingAction> findActivePending(String visitorId, String conversationId);
List<AgentTask> findActiveTasks(String visitorId, String conversationId);
List<TimelineItem> timeline(String visitorId, String conversationId, long afterSequence, int limit);
boolean updateConversationRoute(
        String visitorId,
        String conversationId,
        long expectedVersion,
        String targetAgentCode);
```

内存和 JDBC 实现必须同步修改，并覆盖并发路由版本冲突测试。

### 9.5 迁移版本

落地时先检查实际 Flyway 最大版本。若本功能在 Console Security 之前实施，可使用 `V003__domain_agent_routing.sql`；若已有并行迁移，则使用下一个空闲版本。不得覆盖已执行迁移。

## 10. C 端 Chat UI 设计

### 10.1 设计原则

- 消费者只面对一个集团总 Agent，不需要先选择业务系统。
- 子 Agent 应可感知，但不能让页面像内部组织架构或技术调试器。
- 转接过程简洁、可解释，不展示模型思维链。
- 所有 Agent 名称、简介、图标和推荐问题来自 Bootstrap API，不在 Vue 组件中硬编码集团业务。
- 移动端优先保证消息、当前 Agent 和输入区可达。

### 10.2 空态布局调整

当前“今天想完成什么？”使用几何居中，上方留白过大。调整为视觉重心靠上：

```css
.welcome {
    margin: 0 auto auto;
    padding-top: clamp(72px, 12vh, 120px);
}

@media (max-width: 800px) {
    .welcome {
        padding-top: clamp(44px, 8vh, 72px);
    }
}
```

不要使用固定负边距。页面高度变化时，标题、推荐入口和底部输入区仍需保持稳定间距。

### 10.3 桌面信息架构

```text
┌──────────────────────┬──────────────────────────────────────────────┐
│ 集团总智能体          │ 集团智慧服务                                  │
│ 统一理解与协调        │ ● 服务在线                    当前：总智能体 │
│                      ├──────────────────────────────────────────────┤
│ + 新建会话            │                                              │
│                      │          今天想完成什么？                     │
│ 专业服务              │   直接描述需求，我会协调专业服务             │
│ · 酒店服务            │                                              │
│ · 体育服务            │   [酒店预订] [体育活动] [景区服务] [免税购物]│
│ · 文旅服务            │                                              │
│ · 免税服务            │   信息补全 · 操作确认 · 任务追踪             │
│                      │                                              │
│ 历史会话              ├──────────────────────────────────────────────┤
│ · 三亚酒店预订        │ [描述你希望完成的事情...]              [发送]│
└──────────────────────┴──────────────────────────────────────────────┘
```

侧栏“专业服务”展示可用范围，不要求用户必须先点击。点击某个 Agent 只会填入推荐问题或显式选择目标，不直接在浏览器内完成路由。

### 10.4 转接与回复状态

收到 `agent.route` 后显示一条轻量状态：

```text
Agent Pro 已为你转接至 酒店服务
```

随后助手消息头显示：

```text
酒店服务
由 Agent Pro 协调
```

总 Agent 回答集团公共问题时仍显示 `Agent Pro`。不应让一条消息同时出现多个并列头像。

### 10.5 Agent 切换

- 用户明确提出新领域问题时，显示“已切换至文旅服务”。
- 当前存在待确认写动作时，切换前展示选择：“继续酒店预订”或“暂存并咨询文旅”。
- 暂存能力必须由后端状态支持，前端不能仅隐藏表单。
- 移动端专业服务列表放在侧栏抽屉中，不新增占据首屏的横向 Tab 条。

### 10.6 前端代码拆分

当前 `App.vue` 已同时承担 API、SSE、状态和全部视图。实施多 Agent 时建议拆分：

```text
ui-chat/src/
├── api/chat.ts
├── types/chat.ts
├── composables/useChatRuntime.ts
├── components/AgentSidebar.vue
├── components/ChatTopbar.vue
├── components/WelcomePanel.vue
├── components/MessageList.vue
├── components/AgentRouteNotice.vue
├── components/ResultCard.vue
├── components/PendingActionForm.vue
├── components/ConfirmationDialog.vue
└── components/ComposerBar.vue
```

该拆分应与多 Agent 功能一起完成，不进行无关的全局前端重构。

### 10.7 前端类型

```ts
interface PublicAgent {
  code: string
  displayName: string
  description: string
  iconKey: AgentIconKey
  suggestedPrompts: SuggestedPrompt[]
}

interface AgentIdentity {
  code: string
  name: string
}

interface AgentRoutePayload {
  coordinatorCode: string
  targetAgentCode?: string
  targetAgentName?: string
  routeType: RouteType
  routeSource: RouteSource
  reasonCode: string
}
```

未知 `iconKey` 必须回退到通用图标，不能导致页面渲染失败。

## 11. Console 与运营能力

### 11.1 第一阶段只读运行视图

Console 增加“领域 Agent”运行视图：

- Agent code、展示名、启用状态和 C 端可见状态。
- 注册动作数量及动作模式分布。
- 模型路由器与降级路由器状态。
- 最近路由量、低置信度量、失败量和切换量。
- Agent 注册校验结果。

该页面读取运行时注册表和聚合指标，不提供新增、编辑 Java 动作或任意 Prompt 发布能力。

### 11.2 后续控制面衔接

当现有完整能力计划中的 Agent Application、Prompt、知识库和 MCP 管理完成后，可以为每个领域 Agent 发布版本快照：

```text
Domain Agent Version
├── DomainModule code version
├── Model binding
├── Prompt version
├── Knowledge base version
├── MCP/Tool allowlist
├── Route policy version
└── Evaluation result
```

在线会话只读取已发布版本。草稿变更不能即时影响 C 端流量。

## 12. 安全、权限与隐私

### 12.1 安全边界

- 总 Agent 的路由结果不是授权依据。
- 领域 Agent 的动作选择不是执行许可。
- 所有动作必须重新校验访客、登录态、资格、资源归属和业务状态。
- `COMMIT`、`PAYMENT`、`AFTER_SALE` 必须继续经过任务和确认门禁。
- Agent 切换不能改变既有任务的 `visitorId`、`domainCode`、`actionCode` 和幂等键。
- 外部 API 地址只能来自受控配置，Agent 描述和前端都不能提交 URL。
- 子 Agent 只能访问显式绑定的知识和工具，不默认共享全部集团数据。

### 12.2 Prompt 注入与越权

模型 Prompt 必须明确：

- 只能选择候选 Agent code 或候选 Action code。
- 忽略要求暴露系统提示、密钥、内部地址或切换到未注册 Agent 的内容。
- 不能将用户文本解释为权限声明。
- 不能生成“操作成功”作为业务事实。

服务端必须对结构化输出进行白名单校验，不能只依赖 Prompt 指令。

### 12.3 数据最小化

- 总 Agent 只读取完成路由所需的脱敏上下文摘要。
- 领域 Agent 只读取本领域需要的会话片段和任务摘要。
- 路由审计不保存完整模型响应和思维链。
- Console 默认只展示脱敏消息、reason code 和结构化指标。

## 13. 可观测性与评估

### 13.1 关键 Trace 属性

```text
request.id
conversation.id
visitor.hash
coordinator.code
route.target_agent_code
route.type
route.source
route.reason_code
route.confidence
action.code
task.id
task.status
model.provider
model.name
```

不得把 Cookie、Token、手机号、证件号或完整 Prompt 放入 Trace 属性。

### 13.2 核心指标

| 指标 | 说明 |
| --- | --- |
| `agent_route_total` | 按目标 Agent、来源和结果统计路由量 |
| `agent_route_ambiguous_total` | 需要用户澄清的路由量 |
| `agent_switch_total` | 会话内跨领域切换量 |
| `agent_route_fallback_total` | 模型失败后确定性回退量 |
| `agent_action_ownership_reject_total` | 跨 Agent 动作越界拒绝量 |
| `agent_task_confirmation_rate` | 各领域高风险动作确认率 |
| `agent_task_success_rate` | 各领域任务成功率 |

### 13.3 路由评估集

至少建立以下样本类别：

- 单领域明确问题。
- 多领域同名词，例如“预订”“门票”“会员”。
- 指代当前上下文的问题。
- 明确切换领域的问题。
- 一个请求包含多个领域的问题。
- 不支持、闲聊、投诉和人工服务问题。
- Prompt 注入和越权请求。
- 模型不可用时的确定性回退。

每个集团项目必须根据真实业务补充 golden dataset，不能只使用 Demo 关键词。

## 14. 详细实施计划

### 14.1 阶段 A0：协议冻结与回归基线

目标：在改造前固定现有行为，避免多 Agent 改造破坏确认、幂等和恢复。

任务：

1. 为当前 `ChatOrchestrator` 增加以下回归测试：
   - 查询直执。
   - 缺参补全。
   - 高风险动作等待确认。
   - 错误确认版本冲突。
   - 重复 request ID 幂等。
   - 事件 sequence 顺序与恢复。
2. 为 OpenAI、MiniMax 和确定性解析器增加合法动作白名单测试。
3. 固定现有 SSE payload 快照，作为向后兼容基线。
4. 明确一个请求只执行一个领域动作的 V1 约束。

完成标准：现有测试稳定通过，失败用例能证明确认和访客归属没有旁路。

### 14.2 阶段 A1：领域 Agent 描述与注册表

目标：保留模块归属，建立真实 Agent 与 Action 所有权。

主要文件：

```text
agent-domain-spi/src/main/java/.../domain/DomainAgentDescriptor.java
agent-domain-spi/src/main/java/.../domain/SuggestedPromptDescriptor.java
agent-domain-spi/src/main/java/.../domain/DomainModule.java
agent-runtime/src/main/java/.../agent/DomainAgentRegistry.java
agent-runtime/src/main/java/.../agent/DefaultDomainAgentRegistry.java
agent-runtime/src/main/java/.../agent/RegisteredDomainAgent.java
agent-runtime/src/main/java/.../agent/OwnedAction.java
```

任务：

1. 为 `DomainModule` 增加兼容默认描述。
2. 构建不可变注册表并执行唯一性、前缀和风险模式校验。
3. 将 `ChatOrchestrator` 的扁平 Action Map 替换为注册表查询。
4. 保持现有单模块 Demo 行为不变。
5. 添加重复 Agent、重复 Action、错误前缀和跨领域动作测试。

完成标准：Runtime 在任何执行路径都能从 `actionCode` 找到唯一 `agentCode`。

### 14.3 阶段 A2：总 Agent 路由与领域动作解析

目标：完成“总 Agent 选领域，子 Agent 选动作”的两级路由。

主要文件：

```text
agent-runtime/src/main/java/.../routing/CoordinatorRouter.java
agent-runtime/src/main/java/.../routing/RouteDecision.java
agent-runtime/src/main/java/.../routing/RouteType.java
agent-runtime/src/main/java/.../routing/RouteSource.java
agent-runtime/src/main/java/.../routing/ConversationRoutingContext.java
agent-runtime/src/main/java/.../routing/DeterministicCoordinatorRouter.java
agent-runtime/src/main/java/.../routing/CoordinatorRoutingService.java
agent-infrastructure/src/main/java/.../model/OpenAiCoordinatorRouter.java
agent-infrastructure/src/main/java/.../model/MiniMaxCoordinatorRouter.java
```

任务：

1. 实现确定性 Coordinator Router 作为默认和降级路径。
2. 实现 OpenAI 与 MiniMax 结构化领域路由适配器。
3. 模型只接收公开路由描述，不接收 Action 执行实现和密钥。
4. 目标 Agent 确定后，现有 `IntentResolver` 只接收该 Agent 的 ActionDescriptor。
5. 增加低置信度澄清、当前 Agent 沿用和明确切换策略。
6. 在补参和确认路径中锁定已有 `domainCode`，不重新路由。

完成标准：任何模型输出都不能让酒店 Agent 执行免税 Agent 的动作。

### 14.4 阶段 A3：事件、持久化与恢复

目标：路由决定成为可恢复事实，而不是一次性内存变量。

任务：

1. 新增 `agent.route` 事件和 Agent 元数据。
2. 新增 `RouteDecisionRecord` 与 `RuntimeStore` 方法。
3. 修改 InMemory/JDBC Store、`ChatMessage`、`PendingAction`、`AgentTask` 和事件工厂。
4. 增加 Flyway 增量迁移，并同步更新 `V001__init_core_tables.sql`。
5. 会话路由快照采用版本条件更新。
6. 增加统一时间线查询，恢复历史事件时重建当前 Agent、卡片和任务归属。
7. Outbox payload 同步携带领域和动作归属。

完成标准：服务重启后继续补参或确认时仍由原领域 Agent 处理，且不会重复写动作。

### 14.5 阶段 A4：Chat API 与前端体验

目标：消费者看到统一总 Agent 和真实的专业服务接管过程。

后端任务：

1. 新增 `/api/chat/v1/bootstrap`。
2. 新增显式 Agent 选择与统一时间线接口。
3. 新增 Chat 入站/出站 DTO，不再直接返回 Runtime 内部类型。
4. 会话和历史消息返回可选 Agent 归属。
5. 增加 DTO 校验、访客归属和序列化兼容测试。

前端任务：

1. 将推荐问题改为 Bootstrap API 驱动。
2. 侧栏增加“总智能体”和“专业服务”层级。
3. 空态标题采用视觉靠上布局。
4. 渲染 `agent.route` 转接状态。
5. 助手消息、卡片、表单、确认和任务显示真实 Agent 名称。
6. 未带 Agent 元数据的历史消息回退显示 `Agent Pro`。
7. 拆分 API、类型、状态组合函数和主要视图组件。
8. 验证桌面、`390px` 手机和窄屏安全区。

完成标准：前端不通过关键词推断 Agent；刷新和断流恢复后 Agent 标识保持正确。

### 14.6 阶段 A5：集团多业务 Demo 与 Console

目标：用公开假数据证明一个总 Agent 可以协调多个领域子 Agent。

将 `agent-demo` 拆成四个 `DomainModule` Bean，Java 包仍位于 Demo 模块，但领域 code 使用正式稳定前缀：

```text
hotel
sports
tourism
dutyfree
```

正式动作码仍应使用稳定领域前缀，建议 Demo 直接采用：

```text
hotel.room.search
sports.event.query
tourism.route.recommend
dutyfree.product.query
```

每个 Demo 至少提供：

- 一个查询动作。
- 一个需要补参的动作。
- 一个需要确认的写动作或等待外部结果动作。
- 专属卡片类型和推荐问题。
- 路由正例、相似词歧义和越权反例测试。

Console 增加只读 Agent 注册表和路由指标页。

完成标准：同一会话可以在四个领域之间明确切换，任务与卡片归属正确，所有数据均为公开假数据。

### 14.7 阶段 A6：评估、灰度与默认切换

目标：在生产项目启用前证明新路由不降低安全性和稳定性。

任务：

1. 增加配置 `agent.routing.mode=flat|coordinator`。
2. 初期默认 `flat`，影子执行 Coordinator Router 但不影响线上结果。
3. 比较旧动作路由与新领域路由，记录差异但不记录敏感原文。
4. golden dataset 达标后对测试流量启用 `coordinator`。
5. 观察路由失败、回退、确认和任务成功指标。
6. 最终将新项目默认值切换为 `coordinator`，保留一个发布周期回滚能力。

完成标准：路由准确率、确认门禁、幂等和访客隔离达到验收阈值。

## 15. 文件级改动清单

| 模块 | 文件或目录 | 改动 |
| --- | --- | --- |
| `agent-domain-spi` | `domain/DomainModule.java` | 增加兼容 Agent 描述入口 |
| `agent-domain-spi` | `domain/DomainAgentDescriptor.java` | 新增子 Agent 稳定描述 |
| `agent-runtime` | `agent/*` | Agent 注册表和动作所有权 |
| `agent-runtime` | `routing/*` | 总 Agent 路由、上下文和决定 |
| `agent-runtime` | `chat/ChatOrchestrator.java` | 改为两级路由并委托内部服务 |
| `agent-runtime` | `chat/ChatMessage.java` | 增加 Agent 与 Action 归属 |
| `agent-runtime` | `chat/PendingAction.java` | 持久化 `domainCode` |
| `agent-runtime` | `task/AgentTask.java` | 直接持有 `domainCode` |
| `agent-runtime` | `store/RuntimeStore.java` | 路由事实、消息元数据和会话路由版本 |
| `agent-infrastructure` | `model/*CoordinatorRouter.java` | 模型路由适配和确定性回退 |
| `agent-infrastructure` | `store/*RuntimeStore.java` | 内存和 JDBC 持久化 |
| `agent-boot` | `db/migration` | 新增迁移并同步 V001 初始化定义 |
| `api-chat` | `dto/*` | Bootstrap、消息和 Agent DTO |
| `api-chat` | `ChatController.java` | Bootstrap 与兼容响应 |
| `api-console` | Agent 运行视图 API | 只读注册信息和指标 |
| `agent-demo` | 多领域 Demo | 酒店、体育、文旅、免税公开示例 |
| `ui-chat` | `api/types/composables/components` | 数据驱动 Agent UI 与转接状态 |
| `ui-console` | Agent 运行页 | 注册状态和路由指标 |
| `docs` | 设计书、领域指南、README | 同步当前能力和开发流程 |

## 16. 测试方案

### 16.1 Runtime 单元测试

- Agent code 与 Action code 唯一性。
- Action 前缀与所属 Agent 一致。
- 总 Agent 正确选择目标领域。
- 低置信度返回澄清，不默认选择第一个 Agent。
- 当前 Agent 上下文正确沿用。
- 明确切换能够产生新的 `agent.route`。
- PendingAction 和 Task 锁定领域。
- 跨 Agent 动作输出被服务端拒绝。
- 模型返回非法 Agent/Action 时安全回退。
- 路由版本并发冲突不会覆盖新状态。

### 16.2 API 集成测试

- Bootstrap 只返回公开字段。
- 访客 A 无法读取访客 B 的 Agent 路由和消息。
- SSE 事件顺序为 `agent.route -> form/action/message/card/task`。
- 断流后通过 `afterSequence` 恢复且不重复。
- 老事件无 Agent 元数据时仍可解析。
- 错误响应不包含模型原文、Prompt 或内部地址。

### 16.3 JDBC 测试

- 路由决定唯一约束。
- 会话路由版本条件更新。
- PendingAction、Task 和 Message 的 Agent 归属可恢复。
- 重启后继续确认不重复执行写动作。
- Outbox 重试不重复产生业务任务。
- 历史数据 `agent_code IS NULL` 的兼容读取。

### 16.4 前端测试

- Bootstrap 加载、失败和空 Agent 状态。
- 总 Agent 空态与专业服务列表。
- `agent.route` 展示和 Agent 切换。
- 消息、卡片、表单、确认、任务的 Agent 名称一致。
- 恢复会话后当前 Agent 正确。
- 请求期间按钮防重复提交。
- 桌面、平板、`390px` 和小屏无横向溢出或遮挡。

### 16.5 验证命令

```bash
mvn test
mvn -pl agent-boot -am test
mvn -pl agent-boot -am package
npm --prefix ui-chat run build
npm --prefix ui-console run build
git diff --check
```

## 17. 验收标准

### 17.1 架构验收

- 新增业务只需注册 `DomainModule` 和 `AgentAction`，Runtime 无业务类型分支。
- 每个 Action 都能追溯到唯一 Domain Agent。
- 总 Agent 和领域 Agent 的模型输出都经过白名单校验。
- Controller、Runtime、SPI、Infrastructure 和 UI 依赖方向不变。

### 17.2 功能验收

- 用户无需先选择领域即可发起问题。
- 服务端能生成可恢复的 `agent.route` 事件。
- 前端真实显示当前领域 Agent，不通过本地关键词猜测。
- 会话内可以明确从酒店切换到文旅等其他领域。
- 跨领域复合写请求会拆分和确认，不并行执行。
- 补参、确认、异步等待和结果恢复继续工作。

### 17.3 安全验收

- 访客隔离测试全部通过。
- 跨 Agent Action 越界测试全部拒绝。
- 高风险动作不能绕过确认。
- 重复 request ID 和幂等键不会产生第二个有效写操作。
- 日志、事件、Trace 和错误响应不泄露敏感信息。

### 17.4 质量阈值

- 核心领域路由 golden dataset 准确率不低于 95%。
- 高风险意图不得错误路由后直接执行，门禁覆盖率 100%。
- 未知领域请求不得伪造成功回答。
- 移动端和桌面端横向溢出为 0。
- 新旧协议兼容测试通过。

## 18. 发布、回滚与兼容策略

### 18.1 兼容策略

- `StreamEvent` 顶层结构不变。
- Agent 元数据全部先作为可选字段加入。
- 未识别 `agent.route` 的旧前端会忽略该事件。
- 老消息没有 Agent code 时按总 Agent 展示。
- 老任务的 domain code 可以暂时从 action code 前缀回填。

### 18.2 灰度策略

```text
flat 路由正常执行
        +
coordinator 影子路由只记录差异
        -> 测试流量启用 coordinator
        -> 小比例真实流量
        -> 全量启用
```

影子路由不得调用领域动作，不得生成任务，不得改变会话当前 Agent。

### 18.3 回滚策略

- 应用配置切回 `agent.routing.mode=flat`。
- 新增数据库字段和路由表保持不删除，旧代码可忽略。
- 不回滚已产生的任务、确认和审计事实。
- 路由切换失败不能触发重复业务写入。

## 19. 与现有完整能力计划的关系

本方案是 Runtime 基础能力，应插入现有完整能力计划的 M1 与 M5 之间：

```text
M1 Runtime 与持久化
  -> 本方案：总 Agent 与领域子 Agent
  -> M2 模型与 Prompt
  -> M3/M4 MCP 与知识库
  -> M5 Agent Application 与开放 API
```

其中：

- 第一阶段只依赖现有模型适配器和代码注册表。
- M2 完成后，可以为总 Agent 和子 Agent 绑定版本化 Prompt。
- M3/M4 完成后，可以为子 Agent 绑定专属 MCP 和知识库。
- M5 完成后，可以把整个集团总 Agent 应用作为不可变版本发布。
- M7 Workflow 用于受控跨领域组合流程，不取代本方案的领域路由。

## 20. 推荐的第一批交付范围

建议第一批只交付以下闭环：

1. `DomainAgentDescriptor` 与注册表。
2. 总 Agent 两级路由和确定性回退。
3. `agent.route` 事件与 Agent 元数据。
4. 路由事实、消息、PendingAction 和 Task 的持久化归属。
5. Bootstrap API。
6. Chat 空态上移、总 Agent/专业服务层级和真实转接展示。
7. 酒店、体育、文旅、免税四个公开 Demo Agent。
8. Console 只读 Agent 注册表。
9. 路由 golden dataset 和跨 Agent 越权测试。

暂缓内容：

- 在线创建 Java 领域 Agent。
- 任意 Agent 自由通信。
- 跨领域并行写事务。
- 可视化 Agent 拖拽编排。
- 多租户计费和集团间共享实例。

该范围已经能够清楚证明框架面向集团多业务版图的核心价值，同时保持当前代码优先、安全门禁和模块化单体基线。

## 21. 实施结果（2026-08-01）

第一批交付范围已经落地：

1. `DomainAgentDescriptor`、推荐问题描述和不可变 `DomainAgentRegistry` 已实现，并在启动时校验 Agent/Action 唯一性、动作前缀、公开图标和高风险确认标题。
2. 默认采用 Coordinator 两级路由，支持当前领域沿用、低置信度澄清、显式选择、OpenAI/MiniMax 结构化路由和确定性降级；`agent.routing.mode=flat|coordinator` 保留兼容切换。
3. `agent.route`、领域事件元数据、路由决定、会话路由版本、PendingAction/Task/Message 归属和统一时间线已同时落地到内存与 JDBC Store。
4. Chat 已提供 Bootstrap、显式 Agent 选择和时间线 API；UI 只使用服务端 Agent 元数据展示专业服务、转接、消息、表单、确认、卡片和任务。
5. `agent-demo` 已注册酒店、体育、文旅和免税四个公开假数据领域，每个领域包含查询、补参和确认或等待外部结果动作。
6. Console 已提供只读领域 Agent 注册表、动作模式分布和路由聚合数据。

仍按本方案暂缓：在线创建 Java Agent、任意 Agent 自由通信、跨领域并行写事务、可视化拖拽编排、多租户计费，以及尚未具备真实业务数据的生产路由 golden dataset。当前测试中的路由样本只验证公开 Demo 基线，不能替代具体集团项目的 95% 生产验收集。
