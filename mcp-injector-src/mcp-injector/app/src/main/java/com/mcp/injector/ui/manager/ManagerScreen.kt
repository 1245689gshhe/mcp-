package com.mcp.injector.ui.manager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcp.injector.agent.InjectedApp
import com.mcp.injector.agent.ModuleScope
import com.mcp.injector.ui.components.KeyValueRow
import com.mcp.injector.ui.components.SectionHeader
import com.mcp.injector.ui.components.StatusChip
import com.mcp.injector.ui.theme.semanticColors
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manager 详情页（任务 C 核心，新增）。
 *
 * DEV_PLAN 任务 C 第 4 点 UI：详情（状态、工具清单、完整度比对）、操作
 * （改端口→广播下发、重新注入、提取诊断→回传再注入）、模块作用域开关。
 * 数据全部来自 [ManagerViewModel]；广播未响应时各区块给出降级提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagerScreen(
    vm: ManagerViewModel,
    packageName: String,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(packageName) {
        if (packageName.isNotEmpty()) vm.load(packageName)
    }
    LaunchedEffect(Unit) {
        vm.events.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.app?.appName ?: "应用管理",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val app = state.app
        if (app == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "未找到该应用的注入记录（可能已被移除）",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { InfoCard(app) }

                item {
                    SectionHeader("运行状态")
                    StatusCard(
                        statusJson = state.statusJson,
                        onRefresh = { vm.refreshStatus() },
                    )
                }

                item {
                    SectionHeader("端口管理（广播下发）")
                    PortCard(
                        portInput = state.portInput,
                        onPortChange = { vm.updatePortInput(it) },
                        onApply = { vm.applyPort() },
                    )
                }

                item {
                    SectionHeader("工具清单与完整度")
                    ToolsCard(
                        diagnoseJson = state.diagnoseJson,
                        onRefresh = { vm.refreshDiagnose() },
                    )
                }

                item {
                    SectionHeader("诊断与回传再注入")
                    DiagnoseCard(
                        diagnoseJson = state.diagnoseJson,
                        busy = state.busy,
                        onExtract = { vm.refreshDiagnose() },
                        onReinjectWithDiagnose = { vm.reinject(useDiagnose = true) },
                    )
                }

                item {
                    SectionHeader("重新注入")
                    ReinjectCard(
                        app = app,
                        busy = state.busy,
                        onReinject = { vm.reinject() },
                        onInstall = { vm.installOutput() },
                    )
                }

                item {
                    SectionHeader("模块作用域（NPatch 式）")
                    ModuleScopeCard(
                        scopes = state.scopes,
                        busy = state.busy,
                        onToggle = { id, enabled -> vm.setModuleEnabled(id, enabled) },
                    )
                }

                item {
                    TextButton(
                        onClick = { vm.removeFromHistory() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            modifier = Modifier.width(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "从宿主历史移除记录",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (state.busy) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** 详情页统一卡片：圆角 16 + 浅层级容器色，与首页/设置页一致。 */
@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        content()
    }
}

/** 基础信息卡片：来源/installId/策略/是否 hook/注入时间/签名指纹。 */
@Composable
private fun InfoCard(app: InjectedApp) {
    DetailCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(status = if (app.source == "scan") "同签名" else "历史")
            }
            KeyValueRow("包名", app.packageName)
            KeyValueRow("版本", app.versionName)
            KeyValueRow("installId", app.installId, mono = true)
            KeyValueRow("策略", app.strategy ?: "未知")
            KeyValueRow("启动 Hook", if (app.hookable) "成功" else "未 hook（广播/服务引导）")
            KeyValueRow(
                "注入时间",
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(app.injectedAt)),
            )
            KeyValueRow("签名指纹", app.signatureFingerprint.ifEmpty { "未知" }, mono = true)
        }
    }
}

/** 运行状态卡片：解析 cmd=status 回执；未响应时提示并允许重试。 */
@Composable
private fun StatusCard(statusJson: JSONObject?, onRefresh: () -> Unit) {
    DetailCard {
        Column(modifier = Modifier.padding(16.dp)) {
            if (statusJson == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "目标未响应（未运行 / 未安装 / 未注入 AgentReceiver）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("重试")
                    }
                }
                return@Column
            }
            KeyValueRow("运行中", if (statusJson.optBoolean("running")) "是" else "否")
            KeyValueRow("监听端口", statusJson.optInt("port", 0).toString())
            KeyValueRow("配置端口", statusJson.optInt("configPort", 0).toString())
            KeyValueRow("agent 版本", statusJson.optString("agentVersion", "-"))
            KeyValueRow("目标包名", statusJson.optString("targetPkg", "-"))
            KeyValueRow("运行时长(s)", statusJson.optLong("uptime", 0L).toString())
            Row(modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("刷新")
                }
            }
        }
    }
}

/** 端口管理卡片：输入四位数端口 → 广播 cmd=config 下发并确认。 */
@Composable
private fun PortCard(
    portInput: String,
    onPortChange: (String) -> Unit,
    onApply: () -> Unit,
) {
    DetailCard {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = portInput,
                onValueChange = onPortChange,
                label = { Text("端口（四位数 1024-9999）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                Text("应用并广播下发")
            }
            Text(
                text = "说明：下发后目标侧持久化 mcp_bridge.json 并重启 HTTP 服务，随后自动刷新状态。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** 工具清单卡片：registered/expected/missing/complete（来自 diagnose 内嵌 tools）。 */
@Composable
private fun ToolsCard(diagnoseJson: JSONObject?, onRefresh: () -> Unit) {
    val colors = MaterialTheme.semanticColors
    DetailCard {
        Column(modifier = Modifier.padding(16.dp)) {
            val tools = diagnoseJson?.optJSONObject("tools")
            if (tools == null) {
                Text(
                    text = "诊断未提取（先执行「提取诊断」或等待目标响应）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("提取工具清单")
                }
                return@Column
            }
            val complete = tools.optBoolean("complete", false)
            KeyValueRow(
                key = "完整度",
                value = if (complete) "完整（全部预期工具已注册）" else "缺工具",
                valueColor = if (complete) colors.success else colors.warning,
            )
            KeyValueRow("已注册", joinStrings(tools.optJSONArray("registered")))
            KeyValueRow("预期", joinStrings(tools.optJSONArray("expected")))
            val missing = joinStrings(tools.optJSONArray("missing"))
            KeyValueRow(
                key = "缺失",
                value = missing.ifEmpty { "无" },
                valueColor = if (missing.isEmpty()) colors.success else colors.warning,
                mono = true,
            )
            if (missing.isNotEmpty()) {
                Text(
                    text = "缺失工具未在目标侧注册，可尝试「诊断后重新注入」补齐。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** 诊断卡片：errors/recommendations 展示 + 提取 + 回传再注入。 */
@Composable
private fun DiagnoseCard(
    diagnoseJson: JSONObject?,
    busy: Boolean,
    onExtract: () -> Unit,
    onReinjectWithDiagnose: () -> Unit,
) {
    val colors = MaterialTheme.semanticColors
    DetailCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row {
                OutlinedButton(onClick = onExtract, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("提取诊断")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onReinjectWithDiagnose, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("诊断后重新注入")
                }
            }
            if (diagnoseJson == null) {
                Text(
                    text = "尚未提取诊断数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                return@Column
            }
            Text(
                text = "诊断时间：${formatTs(diagnoseJson.optLong("timestamp", 0L))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            val errors = stringList(diagnoseJson.optJSONArray("errors"))
            if (errors.isNotEmpty()) {
                Text(
                    text = "发现的问题",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                errors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            } else {
                Text(
                    text = "未发现明显问题",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.success,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            val recs = stringList(diagnoseJson.optJSONArray("recommendations"))
            if (recs.isNotEmpty()) {
                Text(
                    text = "建议",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                recs.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
            Text(
                text = "「诊断后重新注入」会把上述问题与建议回灌给 AI 规划器，重新生成方案并执行注入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** 重新注入卡片：无诊断的直接重注入（优先原始导入源，其次注入产物）；重注入会先留原始备份。 */
@Composable
private fun ReinjectCard(
    app: InjectedApp,
    busy: Boolean,
    onReinject: () -> Unit,
    onInstall: () -> Unit,
) {
    DetailCard {
        Column(modifier = Modifier.padding(16.dp)) {
            val source = if (app.source == "scan") "同签名扫描来源，无注入副本" else "有注入副本（源 APK 优先）"
            KeyValueRow("可用源", source)
            Button(onClick = onReinject, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("重新注入")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onInstall,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("安装当前产物")
            }
            Text(
                text = "重新注入会先留一份调整前的「备份」工程（首页工程列表可见，名称右侧带「备份」标签），" +
                    "再生成新的注入版本；两份都能直接安装，新版本不可用时装备份即可回退。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** 模块作用域卡片：每模块勾选 → ModuleScope 落盘 + 广播 cmd=scope 下发。 */
@Composable
private fun ModuleScopeCard(
    scopes: List<Pair<ModuleScope.ModuleInfo, Boolean>>,
    busy: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    DetailCard {
        Column(modifier = Modifier.padding(16.dp)) {
            if (scopes.isEmpty()) {
                Text(
                    text = "暂无模块注册（注入成功后自动启用官方调试模块）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            scopes.forEach { (info, enabled) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = enabled,
                        onCheckedChange = { onToggle(info.id, it) },
                        enabled = !busy,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = info.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = info.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = "说明：勾选变更会写入宿主 scopes.json 并广播下发；最后一个启用模块不会被关闭（official 常驻，避免目标失联）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// ---- helpers ----

private fun joinStrings(arr: org.json.JSONArray?): String =
    if (arr == null || arr.length() == 0) "无"
    else (0 until arr.length()).joinToString(", ") { arr.optString(it) }

private fun stringList(arr: org.json.JSONArray?): List<String> =
    if (arr == null) emptyList()
    else (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }

private fun formatTs(ts: Long): String =
    if (ts <= 0) "-"
    else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))