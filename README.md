# Agent Template Pro

面向 C 端自然语言业务应用的代码优先 Agent 脚手架。当前版本提供完整的模块边界、匿名访客隔离、SSE 聊天协议、参数收集、确认门禁、任务审计协议，以及一个不依赖真实模型和外部系统的公开 Demo。

## 本地启动

要求：JDK 21、Maven 3.9+、Node.js 20+。

```bash
mvn -pl agent-boot -am package
mvn -f agent-boot/pom.xml spring-boot:run
cd ui-chat && npm install && npm run dev
cd ui-console && npm install && npm run dev
```

后端监听 `http://localhost:8080`；Chat 默认 Vite 地址为 `http://localhost:5173`，Console 为 Vite 分配的另一个端口。Console 本地访问密钥默认是 `local-console-key`，部署前必须改为环境变量 `CONSOLE_ACCESS_KEY`。

Demo 可输入：`查询上海天气`、`为张三预约明天`、`查询配送进度`。它展示了字段追问、确认门禁和等待外部结果三种运行状态。

## 模块

- `agent-common`：错误模型、脱敏和纯值对象。
- `agent-domain-spi`：领域模块与受控动作扩展契约。
- `agent-runtime`：会话、参数收集、确认、任务状态机与事件协议。
- `agent-infrastructure`：签名访客身份和可替换的运行时存储实现。
- `api-chat` / `api-console`：分别面向访客和管理端的入站 API。
- `agent-demo`：公开的天气、预约和配送示例领域。
- `agent-boot`：唯一默认启动模块；包含 MySQL 版 Flyway 建表迁移。

默认运行使用内存存储，便于新 clone 的开发者即刻验证交互。接入生产时，实现 `RuntimeStore` 的 MySQL 版本，配置 Redis/模型适配器，并把 Console 本地访问密钥替换为独立管理员认证与 RBAC。

## API 约定

Chat 使用 `POST /api/chat/v1/conversations/{id}/messages:stream` 和 `text/event-stream`。浏览器不提交身份或 Graph thread ID；服务端使用签名 Cookie 解析访客并校验会话归属。高风险动作仅能通过任务 ID、确认版本和用户决定继续执行。

设计和扩展边界见 [智能体开发脚手架系统设计书.md](智能体开发脚手架系统设计书.md)。

## License

This project is licensed under the [MIT License](LICENSE).
