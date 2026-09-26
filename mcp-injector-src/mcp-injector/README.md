# MCP 注入器 v0.9.1（源码重建工程）

原版 v0.9.0 源码丢失，此工程以 jadx 反编译输出为蓝本，重写为干净 Kotlin，并落地 P0/P1 修复与「官方调试模块」（LSP 式管理闭环）。详细任务契约见 [docs/DEV_PLAN.md](docs/DEV_PLAN.md)。

## 本次改动一览

### P0：AI 工具调用修复（子任务 A）
- `data/AiRepository.kt`：`chat()` 支持 OpenAI 风格 `tools` 声明，并同时解析 `message.content` 与 `message.tool_calls`（每项含 `id/function.name/function.arguments`），返回 `ChatResult(content, toolCalls)`
- `data/BuiltInApi.kt`：**彻底删除内置演示 Key**（`sk-injector-built-in-demo-key` 不再存在），仅保留端点/模型模板；未填写 Key 抛 `ApiKeyMissingException` 并引导到设置页
- `ai/AiPlanner.kt`：容忍 ```` ```json ```` 包裹/前后缀文本/字段缺失；解析失败把错误拼回 prompt 重试（≤2 次）；重试仍失败才降级并记录 `lastError`，不再静默弱化为仅 `open_app` 的弱计划
- `ai/InjectionPlan.kt`：新增 `lastError` / `retryCount` 字段

### P1：注入/桥接可靠性（子任务 B + C）
- `apk/DexOps.kt`：注入前依赖预检（bridge 类全名冲突、未解析类型、可 hook 性），输出预检报告；`<clinit>` hook 失败回退注入广播接收器/服务方式
- `apk/ApkInjector.kt`：一次注入 = bridge dex + `assets/mcp_bridge.json` + `assets/.mcp_injector_marker` + manifest 注册 AgentReceiver/BridgeService + 重签名 + 写历史
- `bridge/ToolExecutor.kt`：5 种 target 类型保留，补系统限制适配（10s 超时、后台 Activity 启动、隐式广播→显式、错误捕获回传 `isError`）
- `bridge/Bridge.kt`：失败自愈（端口冲突/无配置时重置状态，允许广播/服务重试拉起）

### 新功能：官方调试模块（LSP 式管理闭环）
架构参考 NPatch（免 root LSPosed 复刻）分层：bridge = 基座注入体，OfficialModule + AgentReceiver = remote-api 通道，宿主 manager 界面 = manager。

**目标应用侧（自洽，无宿主也可独立运行）**
- 注入即自带 `bridge/Bridge.kt` + `bridge/McpHttpServer.kt` + `bridge/AgentReceiver.kt`；读取 `mcp_bridge.json`（缺省默认端口 **1732**），hook 自动拉起，独立监听端口服务 MCP
- `bridge/OfficialModule.kt` 挂载控制端点：
  - `GET /mcp/status` → `{running, port, configPort, agentVersion, targetPkg, uptime}`
  - `GET /mcp/tools` → `{registered, expected, missing, complete}`（工具注入完整性校验）
  - `GET /mcp/diagnose` → `{timestamp, server, tools, config, errors, recommendations}`（问题提取）
  - `POST /mcp/config {"port": N}` → 校验四位数（1024-9999）→ 持久化 → 重启 HTTP 服务（改端口）
- `bridge/AgentReceiver.kt`：`com.mcp.injector.agent.CONTROL` 显式广播控制通道（status/config/diagnose/scope），回执走 resultData

**宿主应用侧（管理台）**
- 主页「已注入应用」列表（`agent/InjectedApps.kt`）：注入历史 + 同签名指纹扫描，显示状态摘要，点击进入管理页
- 管理页（`ui/manager/ManagerScreen.kt`）：详情（状态/工具清单/完整度比对）、改端口（广播下发→校验→复探）、**诊断提取 → 回传 AiPlanner → 重新注入**闭环、模块作用域开关
- 模块作用域（`agent/ModuleScope.kt`）：`scopes.json` 每应用启用列表；模块注册表首个 = official 调试模块，后续模块在此扩展（NPatch 式勾选交互）

## 关键契约

- 默认端口：**1732**（固定四位数），校验 1024-9999
- 无内置密钥/鉴权；API Key/BaseURL/模型由用户在设置页自行填写
- 标记文件：`assets/.mcp_injector_marker` → 首次运行物化到 `filesDir`（指纹识别 + 预期工具来源）
- 配置文件：`mcp_bridge.json`（filesDir 优先，其次打包 asset）

## 构建

本仓库无法在无 Android SDK 环境编译。本地构建：

1. Android Studio 打开本目录（Gradle 8.5+ / JDK 17）
2. 原版图标等资源（mipmap）需从原 APK 的 `res/` 恢复补全
3. 首次构建下载依赖后 `assembleRelease`

## 评审修复（第三轮）

写码 / 美术 / 评审三类子智能体分工复核后按清单闭环：

- **AI 闭环可用性**：确认 `chat→plan→reinject→inject` 全链路点对点贯通；修复 `ApiKeyMissingException` 被 `AiPlanner` 吞掉导致不引导设置的真缺陷（配置类错误直接上抛、不重试不降级）；加固 `tool_calls` 解析（`arguments` 对象化、content 缺失、单条畸形不拖垮整体）。
- **模块作用域职责分离**（修复评审 #9）：`scopeInfo` 只改 `enabledModules`（模块 id 列表），不再覆盖官方模块的 `tools` 声明；新增 `BridgeConfig.enabledModules` 字段并向后兼容；工具完整度判断以 marker 为唯一权威来源，杜绝「改模块后缺工具误报」。
- **模块字段语义归一**（修复评审 #2/#3）：`InjectedApp.modules` 改为存模块 id（`["official"]`），与 `mcp_bridge.json`/marker 语义一致；`scopeInfo` 查询返回 `modules`(启用 id) + `markerModules`(标记详情) 双视图。
- **Settings 空 Key 不打扰**（修复评审 #11）：设置页加载时仅当已配置 Key 才静默拉取模型，空 Key 不弹提示。
- **清理/复核**：删除 `ManagerScreen` 未使用 import；复核 `AgentProbe` 广播已用现行 API（评审 #5 误报）、`AiRepository.extractArgs` 已正确处理字符串（评审 #13 误报）。
- **提示级留痕**（不做底层改动以规避风险）：`ToolExecutor` 参数名 `p0/p1` 与 schema 属性映射、`ApkSigner` 输入流显式 close、`AxmlReader` Int 截断、diagnose `config.file` 未消费——记为后续清单。

## 后续 TODO

- 完整还原其余 UI 细节（Home/Settings 界面与 v0.9.0 一致化）
- 端口扫描冲突提示与 adb reverse 一键引导
- 模块注册表扩展（非 official 业务模块示例）
- dexlib2 隐藏 API 豁免与 Android 16（API 36）适配
