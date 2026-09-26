package com.mcp.injector.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mcp.injector.apk.DexOps
import com.mcp.injector.crash.CrashLogActivity
import com.mcp.injector.ui.components.SectionHeader

/**
 * 设置页（支撑 glue，对齐反编译 SettingsScreen 入口 `SettingsScreen(vm)`）。
 *
 * 无内置 Key：API Key 缺省为空，AI 注入规划前必须在此填写（AiRepository 未填写会抛
 * ApiKeyMissingException 并引导至此页）。
 *
 * 顶部「关于」入口由 [onOpenAbout] 跳转到关于页（版本 / 开发者 / 群聊 / 赞助）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(),
    onOpenAbout: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SectionHeader("关于")
                    SettingsCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenAbout)
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("关于本软件", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = "版本信息 / 开发者 / 交流群 / 赞助",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item {
                    SectionHeader("AI 接口（必填，无内置 Key）")
                    SettingsCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = state.settings.endpoint,
                                onValueChange = vm::updateEndpoint,
                                label = { Text("Base URL（OpenAI 兼容 /v1）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = state.settings.apiKey,
                                onValueChange = vm::updateApiKey,
                                label = { Text("API Key（不内置任何演示 Key）") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "未填写 Key 时，AI 注入规划会失败并提示前往本页填写；Key 仅存于本机 DataStore。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item {
                    SectionHeader("模型")
                    ModelPicker(
                        model = state.settings.model,
                        models = state.models,
                        loading = state.loadingModels,
                        onModelChange = vm::updateModel,
                        onFetch = { vm.fetchModels() },
                    )
                }

                item {
                    SectionHeader("提示词预算（按模型上下文）")
                    ContextPicker(
                        tokens = state.settings.contextTokens,
                        onSelect = vm::updateContextTokens,
                    )
                }

                item {
                    SectionHeader("注入偏好")
                    SettingsCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // 端口输入用本地文本中间态：允许逐字符编辑，仅在解析为合法
                            // 端口(1024-9999)时提交；端口值外部变化时同步回文本。
                            var portText by remember(state.settings.mcpPort) {
                                mutableStateOf(state.settings.mcpPort.toString())
                            }
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { input ->
                                    portText = input
                                    val port = input.toIntOrNull()
                                    if (port != null && port in 1024..9999) vm.updatePort(input)
                                },
                                label = { Text("默认注入端口（四位数 1024-9999）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("优先 service 声明式注入", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "勾选后 AI 规划优先使用 service 策略（hook 回退为广播/服务引导）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = state.settings.preferServiceStrategy,
                                    onCheckedChange = vm::updatePreferService,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("动态取色", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "使用系统 Material You 动态色板",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = state.settings.dynamicColor,
                                    onCheckedChange = vm::updateDynamicColor,
                                )
                            }
                        }
                    }
                }

                item {
                    OutlinedButton(onClick = vm::resetToBuiltIn, modifier = Modifier.fillMaxWidth()) {
                        Text("恢复内置端点与模型模板")
                    }
                }

                item {
                    SectionHeader("诊断")
                    val context = LocalContext.current
                    SettingsCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedButton(
                                onClick = { context.startActivity(Intent(context, CrashLogActivity::class.java)) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("查看 / 清空崩溃日志")
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "应用发生未捕获异常时会自动记录完整堆栈，并在下次启动强制跳转到日志页；可在此主动查看或清空。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** 设置页统一卡片：圆角 16 + 浅层级容器色，与首页/管理页一致。 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
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

/** 模型选择器：下拉 + 「获取模型列表」按钮。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
    model: String,
    models: List<String>,
    loading: Boolean,
    onModelChange: (String) -> Unit,
    onFetch: () -> Unit,
) {
    SettingsCard {
        Column(modifier = Modifier.padding(16.dp)) {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = model,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("模型") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    if (models.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("暂无列表，先点「获取模型列表」") },
                            onClick = { expanded = false },
                        )
                    } else {
                        models.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = {
                                    onModelChange(m)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onFetch, enabled = !loading, modifier = Modifier.weight(1f)) {
                    Text("获取模型列表")
                }
                if (loading) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).width(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

/** 上下文预设档位：token 数 → 展示文案。 */
private val CONTEXT_PRESETS = listOf(
    128_000 to "128k（很省：少塞类与方法）",
    256_000 to "256k",
    512_000 to "512k（推荐）",
    1_000_000 to "1M（放开：多塞类与方法）",
)

/**
 * 模型上下文窗口选择器：档位决定 dex 提示词的候选类/方法预算（见 DexHintBudget）。
 * 窗口越小塞得越少，避免清单摘要 + 方法签名把上下文挤爆。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextPicker(
    tokens: Int,
    onSelect: (Int) -> Unit,
) {
    val budget = DexOps.DexHintBudget.forContext(tokens)
    SettingsCard {
        Column(modifier = Modifier.padding(16.dp)) {
            var expanded by remember { mutableStateOf(false) }
            val label = CONTEXT_PRESETS.firstOrNull { it.first == tokens }?.second
                ?: "${tokens / 1000}k"
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("模型上下文窗口") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    CONTEXT_PRESETS.forEach { (value, text) ->
                        DropdownMenuItem(
                            text = { Text(text) },
                            onClick = {
                                onSelect(value)
                                expanded = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "当前提示词预算：${budget.describe()}。窗口越小塞得越少，避免上下文被撑爆。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}