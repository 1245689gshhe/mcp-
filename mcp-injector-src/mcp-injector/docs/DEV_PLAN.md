# MCP 注入器 v0.9.1 重建开发计划（并行任务）

## 背景

源码丢失，以 jadx 反编译输出为蓝本重建干净 Kotlin 工程。本次并行任务交付：

- P0：修复「AI 无法调用工具」——chat() 支持 tool_calls、删除演示 Key 必然可用逻辑、JSON 解析容错 + 失败信息回灌重试
- P1：注入/桥接可靠性——依赖预检、hook 回退、ToolExecutor 系统限制适配
- 新功能「官方模块」（LSP 式）：
  - 注入时**一并注入调试/官方模块**，目标 App 脱离宿主也能按默认端口独立运行（自洽）
  - 宿主端主页新增「已注入应用」列表（历史 + 指纹识别），提供注入详情与工具清单
  - 端口管理、工具完整性校验、诊断提取 → 回传 → 再注入修改的闭环
  - 模块作用域：按目标应用启停其他模块（首个模块 = official 调试模块）

## NPatch 交互参考（模块作用域与分层）

参考 https://github.com/7723mod/NPatch （免 root LSPosed 复刻）：
- 分层：core（基座）+ patch/meta-loader（注入加载器，dex 注入目标 APK）+ manager（管理端 app）+ remote-api（管理端与目标内 agent 的通信通道）
- 注入即自带 agent，目标应用**不依赖 manager 独立运行**；manager 只在调试/管理时经 remote-api 通道连接
- 模块作用域：manager 中对每个目标 App 维护「启用的模块列表」，勾选后应用——我们的 ModuleScope 同此交互
- 本工程对应：bridge = 基座+注入体；OfficialModule + AgentReceiver = remote-api 通道；宿主 manager 界面 = manager

## 目录结构

- 重建工程根：`/workspace/mcp-injector-rebuild`
- 源码：`app/src/main/java/com/mcp/injector/...`
- 反编译参考：`/data/user/work/decompiled/sources/com/mcp/injector/`（自行 Read 对照，按 jadx 逻辑重写为干净 Kotlin，文件名同 base 名 + `.kt`）
- 本环境无 Android SDK/Gradle，不编译；遵循 Kotlin 语法与下方契约即可

## 共享契约（跨包 API，必须一致）

- `ToolCall(id: String, name: String, argsJson: String)`
- `ToolResult(toolCallId: String, content: String, isError: Boolean = false)`
- `ChatResult(content: String?, toolCalls: List<ToolCall>)`
- `AiRepository.chat(model, system, user, tools: List<McpToolDef>?, ...): ChatResult`
  —— OpenAI 风格 chat.completions；请求带 `tools` 声明；响应同时解析 `message.content` 与 `message.tool_calls`；失败抛可读异常（含 HTTP 状态）
- 桥接端 OfficialModule 控制端点（挂载于 McpHttpServer，路径 `/mcp/*`）：
  - `GET /mcp/status` → `{running, port, configPort, agentVersion, targetPkg, uptime}`
  - `GET /mcp/tools` → `{registered, expected, missing, complete}`
  - `GET /mcp/diagnose` → `{timestamp, server, tools, config, errors, recommendations}`
  - `POST /mcp/config {"port": N}` → 持久化并重启 HTTP 服务（**port 固定四位数，默认 1732，校验 1024-9999**）
- 配置/标记文件（目标 App 私有目录 `filesDir`）：
  - `mcp_bridge.json` → `{port, host, modules: [...]}`，**无内置密钥/鉴权**（authToken 移除），缺省用内置默认端口 1732，**无宿主也正常监听**；鉴权若未来需要由用户自填后另行扩展
  - `.mcp_injector_marker` → `{installId, appVersion, port, injectedAt, modules}`（指纹识别依据之一）
- `AgentReceiver`：exported=true，action `com.mcp.injector.agent.CONTROL`，extras `{cmd: status|config|diagnose|scope, port?}`，宿主经显式广播控制目标 App；回执走 resultData / 有序广播
- `ModuleScope`（宿主端）：`scopes.json` → `{pkg: {enabledModules: [moduleId]}}`；`ModuleInfo{id, name, desc, entryClass}`
- 指纹识别：宿主合并「注入历史」+「与注入器同签名的已安装应用（PackageManager 签名比较）」+「AgentReceiver 广播探测」

## 任务划分（文件所有权，互不冲突，严禁越权修改他人文件）

- 任务 A「AI 层 P0」：`data/AiRepository.kt`、`data/BuiltInApi.kt`、`data/AppSettings.kt`、`data/SettingsRepository.kt`、`ai/AiPlanner.kt`、`ai/ToolCallModels.kt`(新增)、`ai/InjectionPlan.kt`
- 任务 B「桥接层 + 官方模块」：`bridge/Bridge.kt`、`bridge/BridgeConfig.kt`、`bridge/BridgeService.kt`、`bridge/McpHttpServer.kt`、`bridge/ToolRegistry.kt`、`bridge/ToolExecutor.kt`、`bridge/OfficialModule.kt`(新增)、`bridge/AgentReceiver.kt`(新增)
- 任务 C「注入器层 + 宿主端」：`apk/DexOps.kt`、`apk/ApkInjector.kt`、`apk/ApkSigner.kt`、`ui/home/HomeViewModel.kt`、`ui/home/HomeScreen.kt`、`ui/manager/ManagerViewModel.kt`(新增)、`ui/manager/ManagerScreen.kt`(新增)、`agent/ModuleScope.kt`(新增)

## 任务 A 要点（AI 层）

1. `AiRepository.chat`：请求带 `tools` 数组；响应解析 `choices[0].message.content` 与 `message.tool_calls`（数组，每项 `id/function.name/function.arguments` 为 JSON 字符串），返回 `ChatResult`；HTTP 非 2xx / 解析失败抛可读异常
2. **彻底删除内置 Key/API**：`BuiltInApi` 不内置任何演示 Key（原版 `sk-injector-built-in-demo-key` 移除）；用户必须自行在设置中填写 API Key/BaseURL/模型；未填写抛 `ApiKeyMissingException` 并引导到设置页
3. `AiPlanner`：容忍 ```` ```json ```` 包裹、前后缀文本、字段缺失；解析失败把错误拼进 prompt 重试（最多 2 次）；重试仍失败才降级并记录 `lastError`——不得静默降级成仅 open_app 的弱计划
4. `InjectionPlan` 保留原字段（McpToolDef/ToolTarget 等），新增 `lastError`、`retryCount`

## 任务 B 要点（桥接层 + 官方模块）

1. **自洽运行**：目标进程启动即拉起 BridgeService（hook 或 AgentReceiver 均可）；读 `mcp_bridge.json`（缺省用内置默认端口），无宿主也正常监听端口服务 MCP
2. `OfficialModule`：实现 `/mcp/*` 端点；`POST /mcp/config` 改 port 后写文件并重启 HttpServer 线程；诊断含「预期工具 vs 已注册工具」比对（expected 取自标记文件 modules 记录）
3. `ToolExecutor`：5 种 target 类型保留；补系统限制适配（后台 Activity 启动、隐式广播→显式、10s 超时、错误信息捕获回传 `ToolResult.isError`）
4. `AgentReceiver`：显式广播控制入口，返回状态 JSON
5. 仅依赖 framework + 注入时已打包进目标 apk 的类；不得引用宿主独有类；`McpHttpServer`/`ToolExecutor` 用纯 Java 风格（HttpServer 线程 + 反射）保持自包含，具体以反编译代码为准

## 任务 C 要点（注入器层 + 宿主端）

1. `DexOps`：注入前依赖预检（目标 dex 是否已有 bridge 类全名冲突、类引用能否解析），产出预检报告；hook 目标 `<clinit>` 保留，失败回退注入广播接收器/服务方式
2. `ApkInjector`：一次注入 = bridge + OfficialModule + AgentReceiver + 写 `.mcp_injector_marker`；完成后记录历史（包名、签名指纹、installId、端口、模块）
3. `HomeViewModel/HomeScreen`：原项目列表保留；新增「已注入应用」列表（历史 + 同签名扫描），每项显示状态摘要，点击跳 ManagerScreen
4. `ManagerScreen/ManagerViewModel`：详情（状态、工具清单、完整度比对）、操作（改端口→广播下发、重新注入、提取诊断→生成再注入请求→AiPlanner→ApkInjector 闭环）、模块作用域开关
5. `ModuleScope`：模块注册表（首个=official 调试模块）+ 每应用启用列表 + 设置界面

## 验收（各任务自查）

- 遵循 Kotlin 语法与上述契约签名
- 不越权修改其他任务文件
- 返回：完成文件清单 + 关键签名 + 与契约的偏差说明
