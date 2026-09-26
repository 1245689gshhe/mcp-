package com.mcp.injector.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mcp.injector.R

/** 开发者署名信息。 */
private const val DEVELOPER_NAME = "白时"
private const val DEVELOPER_QQ = "3774724272"

/** QQ 群邀请链接（点击后用浏览器打开）。 */
private const val QQ_GROUP_URL =
    "https://qun.qq.com/universal-share/share?ac=1&authKey=JJo%2BDrZXIblXtoyuAEj6uoKi21gjFI15gmHabiwK%2FGWU%2BHwrrgADsPAARyYxsABv&busi_data=eyJncm91cENvZGUiOiIxMDgwNTkwNzk1IiwidG9rZW4iOiJJSTB2aVVxWmZuNGR0eTBOSjR3VFoxTGhRdW9uN1pwSk1ERFdGcDhDYytNeGFGc1RnTlc0b0ErRC93NkMxQ1p4IiwidWluIjoiMzc3NDcyNDI3MiJ9&data=BKGv-tCZ72YmCk3EL-c-JbAfH8SNcMqh2dWzevUzgkRNmqlrhCNxM3mkd6VojozvaMAu7tNSTwgZctWVFuL8FQ&svctype=4&tempid=h5_group_info"

/**
 * 关于页：显示应用图标、版本号、开发者，并提供「加入群聊」与「赞助作者」入口。
 *
 * 版本号从 PackageManager 实时读取（AGP 8 默认不生成 BuildConfig，故不走 BuildConfig）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "未知"
    }
    var showSponsor by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "版本 $version",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "开发者：$DEVELOPER_NAME（$DEVELOPER_QQ）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = { openInBrowser(context, QQ_GROUP_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("加入群聊")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showSponsor = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("赞助作者")
            }
        }
    }

    if (showSponsor) {
        SponsorDialog(onDismiss = { showSponsor = false })
    }
}

/** 赞助弹窗：上方为感谢文案，下方为作者赞赏码。 */
@Composable
private fun SponsorDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        ElevatedCard(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("感谢支持", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "感谢你使用 MCP 注入器。如果这个工具帮到了你，欢迎扫码请作者喝杯奶茶——" +
                        "你的每一份支持，都是持续更新的动力。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Image(
                    painter = painterResource(R.drawable.sponsor_qr),
                    contentDescription = "作者赞赏码",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp)),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("关闭")
                }
            }
        }
    }
}

/** 用系统浏览器打开链接（窗口可能不存在时静默忽略）。 */
private fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}