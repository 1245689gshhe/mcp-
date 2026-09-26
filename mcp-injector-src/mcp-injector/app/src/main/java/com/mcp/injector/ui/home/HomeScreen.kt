package com.mcp.injector.ui.home

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mcp.injector.agent.InjectedApp
import com.mcp.injector.data.Project
import com.mcp.injector.ui.components.EmptyHint
import com.mcp.injector.ui.components.SectionHeader
import com.mcp.injector.ui.components.StatusChip
import com.mcp.injector.ui.components.StrategyChatDialog
import com.mcp.injector.ui.components.ProjectStatusUi
import java.io.File
import kotlinx.coroutines.launch

/** APK 选择器支持的 MIME（对齐反编译产物 APK_MIME）。 */
private val APK_MIME = arrayOf(
    "application/vnd.android.package-archive",
    "application/zip",
    "application/octet-stream",
)

/**
 * 主页（任务 C 核心）。
 *
 * 对齐反编译 HomeScreen 的公开入口 `HomeScreen(vm)` 并新增任务 C 的
 * [onOpenManager] 参数（点击「已注入应用」项跳转 ManagerScreen）：
 * - 上部：「已注入应用」列表（注入历史 + 同签名扫描，来自 HomeViewModel.injectedApps）；
 * - 下部：原工程列表（导入 APK → 注入流水线，ProjectCard 对齐反编译布局）；
 * - 导入按钮走系统文件选择器（APK_MIME）。
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenManager: (String) -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        vm.events.collect { message ->
            snackbar.showSnackbar(message)
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importApk(uri)
    }

    Box {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 已注入应用（历史 + 同签名扫描）----
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader("已注入应用", modifier = Modifier.weight(1f))
                    Button(onClick = { launcher.launch(APK_MIME) }) {
                        Text("导入 APK 注入")
                    }
                }
            }
            item {
                Text(
                    text = "长按下方任意卡片，可打开 AI 调试对话反馈问题并由 AI 调整注入策略",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.injectedApps.isEmpty()) {
                item {
                    EmptyHint(body = "暂无已注入应用：从右下/顶部导入 APK 完成首次注入")
                }
            } else {
                items(state.injectedApps, key = { it.packageName }) { app ->
                    InjectedAppCard(
                        app = app,
                        onClick = { onOpenManager(app.packageName) },
                        onLongClick = { vm.openStrategyChat(app.packageName) },
                    )
                }
            }

            // ---- 原工程列表 ----
            item { Spacer(Modifier.height(8.dp)); SectionHeader("注入工程") }
            if (state.projects.isEmpty()) {
                item { EmptyHint(body = "导入 APK 后在此显示注入进度与产物") }
            } else {
                items(state.projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onInstall = { file ->
                            installApk(vm, file)?.let { message ->
                                scope.launch { snackbar.showSnackbar(message) }
                            }
                        },
                        onDelete = { vm.deleteProject(project.id) },
                        onLongClick = { vm.openStrategyChat(project.packageName) },
                        iconFile = vm.iconOf(project),
                        outputFile = vm.outputFor(project),
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        state.strategyChat?.let { chat ->
            StrategyChatDialog(
                appName = chat.appName,
                contextNote = chat.contextNote,
                turns = chat.turns,
                input = chat.input,
                thinking = chat.thinking,
                applying = chat.applying,
                changes = chat.changes,
                error = chat.error,
                historyOpen = chat.historyOpen,
                sessions = chat.sessions,
                onInputChange = { vm.updateChatInput(it) },
                onSend = { vm.sendChatProblem() },
                onApply = { vm.applyChatPatchAndReinject() },
                onDismiss = { vm.dismissStrategyChat() },
                onToggleHistory = { vm.toggleChatHistory() },
                onOpenSession = { vm.openChatSession(it) },
                onNewSession = { vm.newChatSession() },
                onDeleteSession = { vm.deleteChatSession(it) },
            )
        }
    }
}

/** 已注入应用卡片：来源（历史/扫描）+ 包名/版本 + 端口 + 管理入口；长按打开 AI 调试对话。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InjectedAppCard(app: InjectedApp, onClick: () -> Unit, onLongClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusChip(status = sourceLabel(app.source))
                }
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (app.port > 0) {
                        "端口 ${app.port} · ${app.versionName}"
                    } else {
                        "${app.versionName} · 端口未知（可进管理页探测）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.ArrowForward,
                contentDescription = "管理",
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun sourceLabel(source: String): String = when (source) {
    "scan" -> "同签名"
    else -> "历史"
}

/**
 * 工程卡片（对齐反编译 ProjectCard 布局：图标/名称/包名/StatusChip + 忙碌进度条 + 失败错误 + 展开操作区）。
 * 长按打开 AI 调试对话（反馈问题 → AI 调整注入策略）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: Project,
    onInstall: (File) -> Unit,
    onDelete: () -> Unit,
    onLongClick: () -> Unit,
    iconFile: File?,
    outputFile: File?,
) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (iconFile != null && iconFile.exists()) {
                    val bitmap = remember(iconFile) { BitmapFactory.decodeFile(iconFile.path) }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        PlaceholderIcon()
                    }
                } else {
                    PlaceholderIcon()
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = project.appName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // 「应用并重新注入」会自动留一份调整前的原始备份，名称右侧标出来便于区分
                        if (project.isBackup) {
                            Spacer(Modifier.width(8.dp))
                            BackupChip()
                        }
                    }
                    Text(
                        text = project.packageName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip(status = project.status)
            }
            if (ProjectStatusUi.isBusy(project.status)) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (project.status == Project.Status.FAILED) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = project.error ?: "未知错误",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Row(modifier = Modifier.padding(top = 12.dp)) {
                    if (outputFile != null && outputFile.exists()) {
                        OutlinedButton(onClick = { onInstall(outputFile) }) {
                            Text("安装产物")
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onDelete) {
                        Text("删除工程")
                    }
                }
            }
        }
    }
}

/**
 * 「备份」标签：标记「应用并重新注入」时自动留存的调整前版本。
 * 与新注入版本在卡片名称右侧形成明显区分，便于用户回退安装。
 */
@Composable
private fun BackupChip() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = "备份",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun PlaceholderIcon() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

/** 用 FileProvider 拉起系统安装器（对齐反编译 installApk）；失败返回可读错误，成功返回 null。 */
private fun installApk(vm: HomeViewModel, file: File): String? {
    if (!file.exists()) return "安装失败：产物文件不存在（${file.name}）"
    return runCatching {
        val context = vm.getApplication<android.app.Application>()
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, APK_MIME[0])
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.fold(
        onSuccess = { null },
        onFailure = { t -> "安装失败：${t.message ?: t.javaClass.simpleName}" },
    )
}
