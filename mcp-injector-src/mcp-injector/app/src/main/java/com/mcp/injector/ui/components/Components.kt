package com.mcp.injector.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcp.injector.ui.theme.semanticColors

/**
 * 通用 Compose 组件（任务 C 支撑文件，对齐反编译 ComponentsKt 的公开函数签名）：
 * StatusChip / SectionHeader / EmptyHint / KeyValueRow。
 */

/** 状态胶囊：busy 用主题主色，done 用成功语义色（跟随深色），failed 用错误色，其余次要色。 */
@Composable
fun StatusChip(status: String, modifier: Modifier = Modifier) {
    val busy = ProjectStatusUi.isBusy(status)
    val success = MaterialTheme.semanticColors.success
    val color = when {
        busy -> MaterialTheme.colorScheme.primary
        status == com.mcp.injector.data.Project.Status.DONE -> success
        status == com.mcp.injector.data.Project.Status.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text = ProjectStatusUi.label(status),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 区块标题。 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** 空态提示：body + 可选图标。 */
@Composable
fun EmptyHint(
    body: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        if (icon != null) {
            icon()
            androidx.compose.foundation.layout.Spacer(
                androidx.compose.ui.Modifier.padding(start = 8.dp),
            )
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 键值行：key 次要色，value 可指定颜色，mono 时等宽字体。 */
@Composable
fun KeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    mono: Boolean = false,
) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = key,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (valueColor == Color.Unspecified) {
                MaterialTheme.colorScheme.onSurface
            } else {
                valueColor
            },
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}
