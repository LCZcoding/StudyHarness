# ai-agent-study

基于 Spring AI 的 AI 智能体全栈项目，包含「AI 问题引导解决助手」与「AI 超级智能体（类 Manus）」两个应用，覆盖 ReAct 工具调用循环、多轮会话记忆、RAG 检索增强、MCP 工具接入与 SSE 流式对话。

---

## 系统架构

整体链路：前端 → 后端控制器 → 两大应用（问题引导助手 / 超级智能体）→ 工具系统、记忆存储与外部 AI / 数据服务。

| 分层 | 关键组成 | 职责 |
|---|---|---|
| 前端 | Vue 3 + Vite 5 | 应用中心 + 两个聊天页，原生 `EventSource` 消费 SSE |
| 接口层 | `AiController` | 同步 / SSE 两类对话接口，端口 `8123`，context-path `/api` |
| 应用层 | `LoveApp`、`LiManus` | 引导式问答应用 / 自主规划并调用工具的智能体 |
| 智能体内核 | `BaseAgent` → `ReActAgent` → `ToolCallAgent` | 状态机 + think / act 事件循环 |
| 工具层 | `ToolRegistration` + 8 个本地工具 + MCP | 工厂 + 适配器注册，合并远程 MCP 工具并支持降级 |
| 记忆 | `FileBasedChatMemory`、`ConversationStore` | 文件记忆（Kryo）/ 内存多轮记忆，按 `chatId` 隔离 |
| 模型 | DeepSeek（默认）、DashScope、Ollama | 统一由 `ChatClient` 抽象调用 |

> 👉 **交互式架构图**（支持深 / 浅色主题切换、节点聚焦、缩放与 PNG/SVG 导出）：
> [查看架构图（下载项目后通过浏览器打开）](./.archify/architecture-ai-agent-study/architecture.html)

---

## 一、技术栈

### 后端
- **Spring Boot 3.4.4** + **Java 21**
- **Spring AI 1.1.0**（BOM 统一管理）
- **DeepSeek**（`spring-ai-starter-model-deepseek`，当前默认对话模型 `deepseek-flash`）
- **Spring AI Alibaba 1.1.0.0-RC2**（通义千问 DashScope 接入 + Agent Framework）
- **Ollama**（本地模型支持）
- **MCP**（Model Context Protocol，客户端接入远程工具）
- **iText 9**（PDF 生成，含图片嵌入）
- **Jsoup**（网页抓取）、**Unirest**（HTTP 客户端）、**Hutool**（工具库）
- **Knife4j / OpenAPI 3**（接口文档）
- **PostgreSQL + pgvector**（向量存储，可选）
- **Kryo**（对象序列化，用于文件记忆）

### 前端
- **Vue 3** + **Vue Router 4**
- **Vite 5**
- **Axios**（HTTP 请求）
- 原生 `EventSource`（SSE 流式接收）

---

## 二、项目结构

```
ai-agent-study/
├── src/main/java/com/lcz/yuaiagent/
│   ├── advisor/          # 增强器（日志、复读）
│   │   ├── MyLoggerAdvisor.java
│   │   └── ReReadingAdvisor.java
│   ├── agent/            # 智能体核心（状态机 + ReAct 循环）
│   │   ├── model/AgentState.java
│   │   ├── BaseAgent.java        # 抽象基类：状态管理、事件循环、记忆
│   │   ├── ReActAgent.java       # ReAct 模式：think → act
│   │   ├── ToolCallAgent.java    # 工具调用实现
│   │   ├── LiManus.java          # 超级智能体
│   │   └── ConversationStore.java# 多轮会话记忆
│   ├── app/LoveApp.java # 问题引导解决助手应用（ChatClient + 记忆 + RAG，类名沿用旧版）
│   ├── chatmemory/FileBasedChatMemory.java  # 基于文件的对话记忆
│   ├── config/CorsConfig.java
│   ├── controller/      # 接口层
│   │   ├── AiController.java
│   │   └── HealthController.java
│   ├── demo/            # 大模型调用示例（Ollama / DashScope）
│   ├── rag/             # RAG 向量存储配置
│   ├── tools/           # 工具集（@Tool 注解）
│   │   ├── ToolRegistration.java # 工具注册（工厂 + 适配器 + MCP 合并）
│   │   ├── DateTimeTool / FileOperationTool / PDFGenerationTool
│   │   ├── ResourceDownloadTool / TerminalOperationTool
│   │   ├── TerminateTool / WebScrapingTool / WebSearchTool
│   │   └── FileConstant.java
│   └── YuAiAgentApplication.java
├── src/main/resources/
│   ├── application.yml
│   ├── application-local.yml          # 本地配置（含密钥，已在 .gitignore 中忽略）
│   ├── application-local.example      # 配置模板
│   ├── mcp-servers.json               # MCP 服务配置（已忽略提交）
│   └── wiki/                          # 知识库文档（RAG，已忽略提交）
├── images-search-mcp-server/          # MCP 图片搜索服务（独立 Spring Boot）
└── ai-agent-frontend/                 # Vue3 前端
    └── src/
        ├── api/         # request.js（Axios 实例）/ chat.js（同步接口）/ sse.js（EventSource 封装）
        ├── components/ChatRoom.vue  # 通用聊天室（props 配置化：theme / ssePath / streamMode）
        ├── router/index.js
        ├── utils/chatId.js
        └── views/       # HomeView / GuideChatView / ManusChatView
```

---

## 三、核心功能

### 1. AI 问题引导解决助手

一个"教练型"的学习与答疑对话应用，不直接给答案，而是引导用户自己定位并解决问题：

- **系统提示词**：基础概念多角度讲解（定义 / 原理 / 用途 / 例子 / 误区）并给出学习路径与检索关键词；工程、报错、代码、配置类问题默认不直接给修复答案，而是引导用户补信息、读报错、提假设、查官方文档、做最小验证，从而自己得出结论；问题解决后强制复盘根因与验证方法；
- **会话记忆**：基于 `chatId` 使用 `MessageWindowChatMemory` + `FileBasedChatMemory`（Kryo 序列化到本地文件），保留最近 10 条消息；
- **RAG 增强（可选）**：`resources/wiki/` 下的知识 Markdown 文档可加载到向量库，通过 `QuestionAnswerAdvisor` 检索增强；
- **结构化输出**：`doChatWithReport` 支持将回答解析为 `LoveReport(title, suggestions)`；
- **工具调用**：可挂载 `allTools` 让引导助手也能调用工具。

### 2. AI 超级智能体（LiManus / 类 Manus）

一个自主规划、调用工具、多步执行的通用智能体：

- **ReAct 循环**：`think()` 让模型决定调用哪些工具，`act()` 执行工具并把结果回灌上下文，循环直到模型调用 `doTerminate` 或达到最大步数；
- **手动工具调用**：通过 `DashScopeChatOptions.internalToolExecutionEnabled(false)` 禁用 Spring AI 内置工具执行，自主维护消息上下文；
- **最终文本总结**：工具循环结束后，额外做一次不带工具选项的 LLM 调用，让模型基于工具结果给出自然语言总结；
- **多轮记忆**：每次请求新建 `LiManus` 实例，但通过 `ConversationStore`（内存 Map）按 `chatId` 加载 / 保存完整消息历史，实现跨请求记忆；
- **卡死检测**：连续 3 条相同的助手回复视为卡死，自动注入"换策略"提示词。

---

## 四、智能体架构

```
BaseAgent（状态机 + 事件循环 + 记忆）
   │
   ├─ run(userPrompt)        同步执行
   └─ runStream(userPrompt)  SSE 流式执行
        │
        └── for step in 1..maxSteps:
               step()  ← ReActAgent 实现
                  ├─ think()   ← ToolCallAgent：调用 LLM 决定工具
                  └─ act()     ← ToolCallAgent：执行工具、回灌结果
```

**状态**：`IDLE → RUNNING → FINISHED / ERROR`

**关键设计**：
- `BaseAgent` 控制"何时"执行循环，子类实现"如何"思考与行动；
- `ReActAgent.step()` 在模型不调用工具时直接返回真实文本（`lastThought`），而非写死的占位语；
- `lastStepIsFinalAnswer` 标记用于区分输出是"直接回答"还是"工具结果"，仅工具结果加 `Step N:` 前缀；
- `needsFinalAnswer` 在工具终止 / 超步数时置为 true，循环结束后调用 `produceFinalAnswer()` 生成最终总结。

---

## 五、工具系统

`ToolRegistration` 采用**工厂 + 适配器 + 注册**模式集中管理工具，并合并 MCP 远程工具：

| 工具 | 方法 | 说明 |
|---|---|---|
| WebSearchTool | `searchWeb` | 调用 Tavily 搜索网络 |
| WebScrapingTool | `scrapeWeb` | 抓取网页正文 |
| ResourceDownloadTool | `downloadResource` | 下载远程资源到本地 |
| PDFGenerationTool | `generatePDF` | 生成 PDF，支持嵌入本地图片（iText 9） |
| FileOperationTool | `readFile` / `writeFile` | 读写本地文件 |
| TerminalOperationTool | `executeCommand` | 执行终端命令 |
| DateTimeTool | `getCurrentTime` | 获取当前时间 |
| TerminateTool | `doTerminate` | 终止智能体执行 |
| MCP: images-search | `searchImage` | 调用 `images-search-mcp-server` 搜索图片 |

> MCP 工具通过 `ObjectProvider<SyncMcpToolCallbackProvider>` 延迟获取，远程服务未启动时自动降级为仅本地工具。

---

## 六、后端接口

服务端口 `8123`，context-path `/api`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/ai/love_app/chat/sync?message=&chatId=` | 问题引导助手 · 同步对话 |
| GET | `/api/ai/love_app/chat/sse?message=&chatId=` | 问题引导助手 · SSE 流式对话 |
| GET | `/api/ai/manus/chat?message=&chatId=` | 超级智能体 SSE 流式对话（含多轮记忆） |

> `love_app` 为沿用自早期版本的路径命名（应用早期名为「AI 恋爱大师」），重构为「问题引导解决助手」后未改动路径，以兼容既有前端调用与记忆数据。

接口文档：启动后访问 `http://localhost:8123/api/doc.html`（Knife4j）。

---

## 七、前端

位于 `ai-agent-frontend/`，Vue 3 + Vite。

- **主页** `/`：应用中心，切换两个 AI 应用；
- **问题引导解决助手** `/guide`：进入即自动生成 `chatId`，SSE 调用 `love_app/chat/sse`，清新蓝青主题（`theme-guide`，accent `#0ea5e9`）；
- **超级智能体** `/manus`：SSE 调用 `manus/chat`，支持多轮记忆，Step 步骤与最终总结分区展示（`theme-manus`，科技深色）；
- 两个页面复用同一个 `ChatRoom.vue`：通过传入 `theme` / `ssePath` / `streamMode` 等 props 配置化，无需改动组件内部逻辑；
- SSE 使用浏览器原生 `EventSource`（`api/sse.js` 中封装），并在流正常结束时主动 `close()`，避免自动重连造成重复请求。

开发环境通过 Vite 代理把 `/api` 转发到 `http://localhost:8123`。

```bash
cd ai-agent-frontend
npm install
npm run dev      # http://localhost:5173
```

---

## 八、快速开始

### 1. 环境要求
- JDK 21
- Maven 3.9+
- Node.js 18+
- DeepSeek API Key（默认对话模型）
- （可选）DashScope API Key、Tavily API Key、Ollama、PostgreSQL + pgvector

### 2. 配置

复制模板并填写真实密钥：

```bash
cp src/main/resources/application-local.example src/main/resources/application-local.yml
```

需要配置的项（当前默认对话模型为 DeepSeek）：

```yaml
spring:
  ai:
    deepseek:            # 默认对话模型（AiController / LoveApp / LiManus 均使用）
      api-key: 你的DeepSeekKey
      chat:
        model: deepseek-flash
    dashscope:           # 通义千问，可选（Agent Framework / 示例演示用）
      api-key: 你的通义千问Key
      chat:
        options:
          model: qwen-plus
    ollama:              # 本地模型，可选
      base-url: http://localhost:11434
      chat:
        model: qwen3.5

tavily:
  api-key: 你的TavilyKey   # WebSearchTool 需要
```

> ⚠️ **安全提醒**：`application-local.yml` 与 `mcp-servers.json` 已在 `.gitignore` 中忽略，请勿提交真实密钥；若密钥曾被提交或外泄，建议及时轮换。

### 3. 启动后端

```bash
./mvnw spring-boot:run
# 或 Windows
mvnw.cmd spring-boot:run
```

### 4. 启动前端

```bash
cd ai-agent-frontend
npm install
npm run dev
```

浏览器打开 `http://localhost:5173`。

---

## 九、MCP 服务

`images-search-mcp-server` 是一个独立的 Spring Boot MCP 服务，提供 `searchImage` 工具（基于 Unsplash）。

在 `mcp-servers.json` 中以 stdio 方式配置：

```json
"yu-image-search-mcp-server": {
  "command": "java",
  "args": [
    "-Dspring.ai.mcp.server.stdio=true",
    "-Dspring.main.web-application-type=none",
    "-Dlogging.pattern.console=",
    "-jar",
    "images-search-mcp-server/target/images-search-mcp-server-0.0.1-SNAPSHOT.jar"
  ]
}
```

需要先 `mvn package` 打包该子模块，再启动主服务时会自动拉起。

---

## 十、设计要点

1. **Spring AI 1.1.0 适配**：适配新 API（如 `ToolCallbacks.from`、`SyncMcpToolCallbackProvider`、`QuestionAnswerAdvisor` 等），早期 starter 的自动注册已移除，因此手写工具注册。
2. **自主 ReAct 工具调用**：`ToolCallAgent` 手动实现 think / act 循环，禁用内置工具执行以获得完全可控的上下文。
3. **真实文本回答 + 最终总结**：模型直接回答时返回其真实文本而非占位语；工具执行完毕后追加一次无工具的 LLM 调用生成总结。
4. **多轮记忆**：`ConversationStore` 按 `chatId` 隔离会话，支持跨请求多轮对话。
5. **前端组件复用**：`ChatRoom.vue` 通过 props 配置主题与接口，两个应用共用同一套聊天 UI。
6. **工具扩展**：`PDFGenerationTool` 支持图片嵌入；合并 MCP 图片搜索工具；远程不可用时降级为本地工具。
7. **模型切换**：对话模型由 DashScope 通义千问切换为 **DeepSeek**（`deepseek-flash`），降低调用成本。

---

## License

本项目基于 [MIT License](./LICENSE) 开源，仅作学习交流使用。
